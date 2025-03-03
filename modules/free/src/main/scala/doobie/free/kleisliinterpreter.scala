// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.free

// Library imports
import cats.~>
import cats.data.Kleisli
import cats.effect.kernel.{ Poll, Sync }
import cats.free.Free
import doobie.WeakAsync
import doobie.util.log.{LogEvent, LogHandlerM}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

// Types referenced in the JDBC API
import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.net.URL
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Clob
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Date
import java.sql.Driver
import java.sql.NClob
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.RowId
import java.sql.SQLData
import java.sql.SQLInput
import java.sql.SQLOutput
import java.sql.SQLType
import java.sql.SQLXML
import java.sql.Savepoint
import java.sql.Statement
import java.sql.Struct
import java.sql.Time
import java.sql.Timestamp
import java.sql.{ Array => SqlArray }
import java.util.Calendar
import java.util.Map
import java.util.Properties
import java.util.concurrent.Executor

// Algebras and free monads thereof referenced by our interpreter.
import doobie.free.nclob.{ NClobIO, NClobOp }
import doobie.free.blob.{ BlobIO, BlobOp }
import doobie.free.clob.{ ClobIO, ClobOp }
import doobie.free.databasemetadata.{ DatabaseMetaDataIO, DatabaseMetaDataOp }
import doobie.free.driver.{ DriverIO, DriverOp }
import doobie.free.ref.{ RefIO, RefOp }
import doobie.free.sqldata.{ SQLDataIO, SQLDataOp }
import doobie.free.sqlinput.{ SQLInputIO, SQLInputOp }
import doobie.free.sqloutput.{ SQLOutputIO, SQLOutputOp }
import doobie.free.connection.{ ConnectionIO, ConnectionOp }
import doobie.free.statement.{ StatementIO, StatementOp }
import doobie.free.preparedstatement.{ PreparedStatementIO, PreparedStatementOp }
import doobie.free.callablestatement.{ CallableStatementIO, CallableStatementOp }
import doobie.free.resultset.{ ResultSetIO, ResultSetOp }

object KleisliInterpreter {
  def apply[M[_]: WeakAsync](logHandler: LogHandlerM[M]): KleisliInterpreter[M] =
    new KleisliInterpreter[M](logHandler)
}

// Family of interpreters into Kleisli arrows for some monad M.
class KleisliInterpreter[M[_]](logHandler: LogHandlerM[M])(implicit val asyncM: WeakAsync[M]) { outer =>
  import WeakAsync._

  // The 14 interpreters, with definitions below. These can be overridden to customize behavior.
  lazy val NClobInterpreter: NClobOp ~> Kleisli[M, NClob, *] = new NClobInterpreter { }
  lazy val BlobInterpreter: BlobOp ~> Kleisli[M, Blob, *] = new BlobInterpreter { }
  lazy val ClobInterpreter: ClobOp ~> Kleisli[M, Clob, *] = new ClobInterpreter { }
  lazy val DatabaseMetaDataInterpreter: DatabaseMetaDataOp ~> Kleisli[M, DatabaseMetaData, *] = new DatabaseMetaDataInterpreter { }
  lazy val DriverInterpreter: DriverOp ~> Kleisli[M, Driver, *] = new DriverInterpreter { }
  lazy val RefInterpreter: RefOp ~> Kleisli[M, Ref, *] = new RefInterpreter { }
  lazy val SQLDataInterpreter: SQLDataOp ~> Kleisli[M, SQLData, *] = new SQLDataInterpreter { }
  lazy val SQLInputInterpreter: SQLInputOp ~> Kleisli[M, SQLInput, *] = new SQLInputInterpreter { }
  lazy val SQLOutputInterpreter: SQLOutputOp ~> Kleisli[M, SQLOutput, *] = new SQLOutputInterpreter { }
  lazy val ConnectionInterpreter: ConnectionOp ~> Kleisli[M, Connection, *] = new ConnectionInterpreter { }
  lazy val StatementInterpreter: StatementOp ~> Kleisli[M, Statement, *] = new StatementInterpreter { }
  lazy val PreparedStatementInterpreter: PreparedStatementOp ~> Kleisli[M, PreparedStatement, *] = new PreparedStatementInterpreter { }
  lazy val CallableStatementInterpreter: CallableStatementOp ~> Kleisli[M, CallableStatement, *] = new CallableStatementInterpreter { }
  lazy val ResultSetInterpreter: ResultSetOp ~> Kleisli[M, ResultSet, *] = new ResultSetInterpreter { }

  // Some methods are common to all interpreters and can be overridden to change behavior globally.
  def primitive[J, A](f: J => A): Kleisli[M, J, A] = Kleisli { a =>
    // primitive JDBC methods throw exceptions and so do we when reading values
    // so catch any non-fatal exceptions and lift them into the effect
    try {
      asyncM.blocking(f(a))
    } catch {
      case scala.util.control.NonFatal(e) => asyncM.raiseError(e)
    }
  }
  def raw[J, A](f: J => A): Kleisli[M, J, A] = primitive(f)
  def raiseError[J, A](e: Throwable): Kleisli[M, J, A] = Kleisli(_ => asyncM.raiseError(e))
  def monotonic[J]: Kleisli[M, J, FiniteDuration] = Kleisli(_ => asyncM.monotonic)
  def realTime[J]: Kleisli[M, J, FiniteDuration] = Kleisli(_ => asyncM.realTime)
  def delay[J, A](thunk: => A): Kleisli[M, J, A] = Kleisli(_ => asyncM.delay(thunk))
  def suspend[J, A](hint: Sync.Type)(thunk: => A): Kleisli[M, J, A] = Kleisli(_ => asyncM.suspend(hint)(thunk))
  def canceled[J]: Kleisli[M, J, Unit] = Kleisli(_ => asyncM.canceled)

  // for operations using free structures we call the interpreter recursively
  def handleErrorWith[G[_], J, A](interpreter: G ~> Kleisli[M, J, *])(fa: Free[G, A])(f: Throwable => Free[G, A]): Kleisli[M, J, A] = Kleisli (j =>
    asyncM.handleErrorWith(fa.foldMap(interpreter).run(j))(f.andThen(_.foldMap(interpreter).run(j)))
  )
  def forceR[G[_], J, A, B](interpreter: G ~> Kleisli[M, J, *])(fa: Free[G, A])(fb: Free[G, B]): Kleisli[M, J, B] = Kleisli (j =>
    asyncM.forceR(fa.foldMap(interpreter).run(j))(fb.foldMap(interpreter).run(j))
  )
  def uncancelable[G[_], J, A](interpreter: G ~> Kleisli[M, J, *], capture: Poll[M] => Poll[Free[G, *]])(body: Poll[Free[G, *]] => Free[G, A]): Kleisli[M, J, A] = Kleisli(j =>  
    asyncM.uncancelable(body.compose(capture).andThen(_.foldMap(interpreter).run(j)))
  )
  def poll[G[_], J, A](interpreter: G ~> Kleisli[M, J, *])(mpoll: Any, fa: Free[G, A]): Kleisli[M, J, A] = Kleisli(j => 
    mpoll.asInstanceOf[Poll[M]].apply(fa.foldMap(interpreter).run(j))
  )
  def onCancel[G[_], J, A](interpreter: G ~> Kleisli[M, J, *])(fa: Free[G, A], fin: Free[G, Unit]): Kleisli[M, J, A] = Kleisli (j =>
    asyncM.onCancel(fa.foldMap(interpreter).run(j), fin.foldMap(interpreter).run(j))
  )
  def fromFuture[G[_], J, A](interpreter: G ~> Kleisli[M, J, *])(fut: Free[G, Future[A]]): Kleisli[M, J, A] = Kleisli(j =>
    asyncM.fromFuture(fut.foldMap(interpreter).run(j))
  )
  def embed[J, A](e: Embedded[A]): Kleisli[M, J, A] =
    e match {
      case Embedded.NClob(j, fa) => Kleisli(_ => fa.foldMap(NClobInterpreter).run(j))
      case Embedded.Blob(j, fa) => Kleisli(_ => fa.foldMap(BlobInterpreter).run(j))
      case Embedded.Clob(j, fa) => Kleisli(_ => fa.foldMap(ClobInterpreter).run(j))
      case Embedded.DatabaseMetaData(j, fa) => Kleisli(_ => fa.foldMap(DatabaseMetaDataInterpreter).run(j))
      case Embedded.Driver(j, fa) => Kleisli(_ => fa.foldMap(DriverInterpreter).run(j))
      case Embedded.Ref(j, fa) => Kleisli(_ => fa.foldMap(RefInterpreter).run(j))
      case Embedded.SQLData(j, fa) => Kleisli(_ => fa.foldMap(SQLDataInterpreter).run(j))
      case Embedded.SQLInput(j, fa) => Kleisli(_ => fa.foldMap(SQLInputInterpreter).run(j))
      case Embedded.SQLOutput(j, fa) => Kleisli(_ => fa.foldMap(SQLOutputInterpreter).run(j))
      case Embedded.Connection(j, fa) => Kleisli(_ => fa.foldMap(ConnectionInterpreter).run(j))
      case Embedded.Statement(j, fa) => Kleisli(_ => fa.foldMap(StatementInterpreter).run(j))
      case Embedded.PreparedStatement(j, fa) => Kleisli(_ => fa.foldMap(PreparedStatementInterpreter).run(j))
      case Embedded.CallableStatement(j, fa) => Kleisli(_ => fa.foldMap(CallableStatementInterpreter).run(j))
      case Embedded.ResultSet(j, fa) => Kleisli(_ => fa.foldMap(ResultSetInterpreter).run(j))
    }

  // Interpreters
  trait NClobInterpreter extends NClobOp.Visitor[Kleisli[M, NClob, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: NClob => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[NClob]
    override def realTime = outer.realTime[NClob]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[NClob]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using NClobIO we must call ourself recursively
    override def handleErrorWith[A](fa: NClobIO[A])(f: Throwable => NClobIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: NClobIO[A])(fb: NClobIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[NClobIO] => NClobIO[A]) = outer.uncancelable(this, doobie.free.nclob.capturePoll)(body)
    override def poll[A](poll: Any, fa: NClobIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: NClobIO[A], fin: NClobIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: NClobIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def free = primitive(_.free)
    override def getAsciiStream = primitive(_.getAsciiStream)
    override def getCharacterStream = primitive(_.getCharacterStream)
    override def getCharacterStream(a: Long, b: Long) = primitive(_.getCharacterStream(a, b))
    override def getSubString(a: Long, b: Int) = primitive(_.getSubString(a, b))
    override def length = primitive(_.length)
    override def position(a: Clob, b: Long) = primitive(_.position(a, b))
    override def position(a: String, b: Long) = primitive(_.position(a, b))
    override def setAsciiStream(a: Long) = primitive(_.setAsciiStream(a))
    override def setCharacterStream(a: Long) = primitive(_.setCharacterStream(a))
    override def setString(a: Long, b: String) = primitive(_.setString(a, b))
    override def setString(a: Long, b: String, c: Int, d: Int) = primitive(_.setString(a, b, c, d))
    override def truncate(a: Long) = primitive(_.truncate(a))

  }

  trait BlobInterpreter extends BlobOp.Visitor[Kleisli[M, Blob, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: Blob => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[Blob]
    override def realTime = outer.realTime[Blob]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[Blob]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using BlobIO we must call ourself recursively
    override def handleErrorWith[A](fa: BlobIO[A])(f: Throwable => BlobIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: BlobIO[A])(fb: BlobIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[BlobIO] => BlobIO[A]) = outer.uncancelable(this, doobie.free.blob.capturePoll)(body)
    override def poll[A](poll: Any, fa: BlobIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: BlobIO[A], fin: BlobIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: BlobIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def free = primitive(_.free)
    override def getBinaryStream = primitive(_.getBinaryStream)
    override def getBinaryStream(a: Long, b: Long) = primitive(_.getBinaryStream(a, b))
    override def getBytes(a: Long, b: Int) = primitive(_.getBytes(a, b))
    override def length = primitive(_.length)
    override def position(a: Array[Byte], b: Long) = primitive(_.position(a, b))
    override def position(a: Blob, b: Long) = primitive(_.position(a, b))
    override def setBinaryStream(a: Long) = primitive(_.setBinaryStream(a))
    override def setBytes(a: Long, b: Array[Byte]) = primitive(_.setBytes(a, b))
    override def setBytes(a: Long, b: Array[Byte], c: Int, d: Int) = primitive(_.setBytes(a, b, c, d))
    override def truncate(a: Long) = primitive(_.truncate(a))

  }

  trait ClobInterpreter extends ClobOp.Visitor[Kleisli[M, Clob, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: Clob => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[Clob]
    override def realTime = outer.realTime[Clob]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[Clob]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using ClobIO we must call ourself recursively
    override def handleErrorWith[A](fa: ClobIO[A])(f: Throwable => ClobIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: ClobIO[A])(fb: ClobIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[ClobIO] => ClobIO[A]) = outer.uncancelable(this, doobie.free.clob.capturePoll)(body)
    override def poll[A](poll: Any, fa: ClobIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: ClobIO[A], fin: ClobIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: ClobIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def free = primitive(_.free)
    override def getAsciiStream = primitive(_.getAsciiStream)
    override def getCharacterStream = primitive(_.getCharacterStream)
    override def getCharacterStream(a: Long, b: Long) = primitive(_.getCharacterStream(a, b))
    override def getSubString(a: Long, b: Int) = primitive(_.getSubString(a, b))
    override def length = primitive(_.length)
    override def position(a: Clob, b: Long) = primitive(_.position(a, b))
    override def position(a: String, b: Long) = primitive(_.position(a, b))
    override def setAsciiStream(a: Long) = primitive(_.setAsciiStream(a))
    override def setCharacterStream(a: Long) = primitive(_.setCharacterStream(a))
    override def setString(a: Long, b: String) = primitive(_.setString(a, b))
    override def setString(a: Long, b: String, c: Int, d: Int) = primitive(_.setString(a, b, c, d))
    override def truncate(a: Long) = primitive(_.truncate(a))

  }

  trait DatabaseMetaDataInterpreter extends DatabaseMetaDataOp.Visitor[Kleisli[M, DatabaseMetaData, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: DatabaseMetaData => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[DatabaseMetaData]
    override def realTime = outer.realTime[DatabaseMetaData]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[DatabaseMetaData]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using DatabaseMetaDataIO we must call ourself recursively
    override def handleErrorWith[A](fa: DatabaseMetaDataIO[A])(f: Throwable => DatabaseMetaDataIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: DatabaseMetaDataIO[A])(fb: DatabaseMetaDataIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[DatabaseMetaDataIO] => DatabaseMetaDataIO[A]) = outer.uncancelable(this, doobie.free.databasemetadata.capturePoll)(body)
    override def poll[A](poll: Any, fa: DatabaseMetaDataIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: DatabaseMetaDataIO[A], fin: DatabaseMetaDataIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: DatabaseMetaDataIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def allProceduresAreCallable = primitive(_.allProceduresAreCallable)
    override def allTablesAreSelectable = primitive(_.allTablesAreSelectable)
    override def autoCommitFailureClosesAllResultSets = primitive(_.autoCommitFailureClosesAllResultSets)
    override def dataDefinitionCausesTransactionCommit = primitive(_.dataDefinitionCausesTransactionCommit)
    override def dataDefinitionIgnoredInTransactions = primitive(_.dataDefinitionIgnoredInTransactions)
    override def deletesAreDetected(a: Int) = primitive(_.deletesAreDetected(a))
    override def doesMaxRowSizeIncludeBlobs = primitive(_.doesMaxRowSizeIncludeBlobs)
    override def generatedKeyAlwaysReturned = primitive(_.generatedKeyAlwaysReturned)
    override def getAttributes(a: String, b: String, c: String, d: String) = primitive(_.getAttributes(a, b, c, d))
    override def getBestRowIdentifier(a: String, b: String, c: String, d: Int, e: Boolean) = primitive(_.getBestRowIdentifier(a, b, c, d, e))
    override def getCatalogSeparator = primitive(_.getCatalogSeparator)
    override def getCatalogTerm = primitive(_.getCatalogTerm)
    override def getCatalogs = primitive(_.getCatalogs)
    override def getClientInfoProperties = primitive(_.getClientInfoProperties)
    override def getColumnPrivileges(a: String, b: String, c: String, d: String) = primitive(_.getColumnPrivileges(a, b, c, d))
    override def getColumns(a: String, b: String, c: String, d: String) = primitive(_.getColumns(a, b, c, d))
    override def getConnection = primitive(_.getConnection)
    override def getCrossReference(a: String, b: String, c: String, d: String, e: String, f: String) = primitive(_.getCrossReference(a, b, c, d, e, f))
    override def getDatabaseMajorVersion = primitive(_.getDatabaseMajorVersion)
    override def getDatabaseMinorVersion = primitive(_.getDatabaseMinorVersion)
    override def getDatabaseProductName = primitive(_.getDatabaseProductName)
    override def getDatabaseProductVersion = primitive(_.getDatabaseProductVersion)
    override def getDefaultTransactionIsolation = primitive(_.getDefaultTransactionIsolation)
    override def getDriverMajorVersion = primitive(_.getDriverMajorVersion)
    override def getDriverMinorVersion = primitive(_.getDriverMinorVersion)
    override def getDriverName = primitive(_.getDriverName)
    override def getDriverVersion = primitive(_.getDriverVersion)
    override def getExportedKeys(a: String, b: String, c: String) = primitive(_.getExportedKeys(a, b, c))
    override def getExtraNameCharacters = primitive(_.getExtraNameCharacters)
    override def getFunctionColumns(a: String, b: String, c: String, d: String) = primitive(_.getFunctionColumns(a, b, c, d))
    override def getFunctions(a: String, b: String, c: String) = primitive(_.getFunctions(a, b, c))
    override def getIdentifierQuoteString = primitive(_.getIdentifierQuoteString)
    override def getImportedKeys(a: String, b: String, c: String) = primitive(_.getImportedKeys(a, b, c))
    override def getIndexInfo(a: String, b: String, c: String, d: Boolean, e: Boolean) = primitive(_.getIndexInfo(a, b, c, d, e))
    override def getJDBCMajorVersion = primitive(_.getJDBCMajorVersion)
    override def getJDBCMinorVersion = primitive(_.getJDBCMinorVersion)
    override def getMaxBinaryLiteralLength = primitive(_.getMaxBinaryLiteralLength)
    override def getMaxCatalogNameLength = primitive(_.getMaxCatalogNameLength)
    override def getMaxCharLiteralLength = primitive(_.getMaxCharLiteralLength)
    override def getMaxColumnNameLength = primitive(_.getMaxColumnNameLength)
    override def getMaxColumnsInGroupBy = primitive(_.getMaxColumnsInGroupBy)
    override def getMaxColumnsInIndex = primitive(_.getMaxColumnsInIndex)
    override def getMaxColumnsInOrderBy = primitive(_.getMaxColumnsInOrderBy)
    override def getMaxColumnsInSelect = primitive(_.getMaxColumnsInSelect)
    override def getMaxColumnsInTable = primitive(_.getMaxColumnsInTable)
    override def getMaxConnections = primitive(_.getMaxConnections)
    override def getMaxCursorNameLength = primitive(_.getMaxCursorNameLength)
    override def getMaxIndexLength = primitive(_.getMaxIndexLength)
    override def getMaxLogicalLobSize = primitive(_.getMaxLogicalLobSize)
    override def getMaxProcedureNameLength = primitive(_.getMaxProcedureNameLength)
    override def getMaxRowSize = primitive(_.getMaxRowSize)
    override def getMaxSchemaNameLength = primitive(_.getMaxSchemaNameLength)
    override def getMaxStatementLength = primitive(_.getMaxStatementLength)
    override def getMaxStatements = primitive(_.getMaxStatements)
    override def getMaxTableNameLength = primitive(_.getMaxTableNameLength)
    override def getMaxTablesInSelect = primitive(_.getMaxTablesInSelect)
    override def getMaxUserNameLength = primitive(_.getMaxUserNameLength)
    override def getNumericFunctions = primitive(_.getNumericFunctions)
    override def getPrimaryKeys(a: String, b: String, c: String) = primitive(_.getPrimaryKeys(a, b, c))
    override def getProcedureColumns(a: String, b: String, c: String, d: String) = primitive(_.getProcedureColumns(a, b, c, d))
    override def getProcedureTerm = primitive(_.getProcedureTerm)
    override def getProcedures(a: String, b: String, c: String) = primitive(_.getProcedures(a, b, c))
    override def getPseudoColumns(a: String, b: String, c: String, d: String) = primitive(_.getPseudoColumns(a, b, c, d))
    override def getResultSetHoldability = primitive(_.getResultSetHoldability)
    override def getRowIdLifetime = primitive(_.getRowIdLifetime)
    override def getSQLKeywords = primitive(_.getSQLKeywords)
    override def getSQLStateType = primitive(_.getSQLStateType)
    override def getSchemaTerm = primitive(_.getSchemaTerm)
    override def getSchemas = primitive(_.getSchemas)
    override def getSchemas(a: String, b: String) = primitive(_.getSchemas(a, b))
    override def getSearchStringEscape = primitive(_.getSearchStringEscape)
    override def getStringFunctions = primitive(_.getStringFunctions)
    override def getSuperTables(a: String, b: String, c: String) = primitive(_.getSuperTables(a, b, c))
    override def getSuperTypes(a: String, b: String, c: String) = primitive(_.getSuperTypes(a, b, c))
    override def getSystemFunctions = primitive(_.getSystemFunctions)
    override def getTablePrivileges(a: String, b: String, c: String) = primitive(_.getTablePrivileges(a, b, c))
    override def getTableTypes = primitive(_.getTableTypes)
    override def getTables(a: String, b: String, c: String, d: Array[String]) = primitive(_.getTables(a, b, c, d))
    override def getTimeDateFunctions = primitive(_.getTimeDateFunctions)
    override def getTypeInfo = primitive(_.getTypeInfo)
    override def getUDTs(a: String, b: String, c: String, d: Array[Int]) = primitive(_.getUDTs(a, b, c, d))
    override def getURL = primitive(_.getURL)
    override def getUserName = primitive(_.getUserName)
    override def getVersionColumns(a: String, b: String, c: String) = primitive(_.getVersionColumns(a, b, c))
    override def insertsAreDetected(a: Int) = primitive(_.insertsAreDetected(a))
    override def isCatalogAtStart = primitive(_.isCatalogAtStart)
    override def isReadOnly = primitive(_.isReadOnly)
    override def isWrapperFor(a: Class[_]) = primitive(_.isWrapperFor(a))
    override def locatorsUpdateCopy = primitive(_.locatorsUpdateCopy)
    override def nullPlusNonNullIsNull = primitive(_.nullPlusNonNullIsNull)
    override def nullsAreSortedAtEnd = primitive(_.nullsAreSortedAtEnd)
    override def nullsAreSortedAtStart = primitive(_.nullsAreSortedAtStart)
    override def nullsAreSortedHigh = primitive(_.nullsAreSortedHigh)
    override def nullsAreSortedLow = primitive(_.nullsAreSortedLow)
    override def othersDeletesAreVisible(a: Int) = primitive(_.othersDeletesAreVisible(a))
    override def othersInsertsAreVisible(a: Int) = primitive(_.othersInsertsAreVisible(a))
    override def othersUpdatesAreVisible(a: Int) = primitive(_.othersUpdatesAreVisible(a))
    override def ownDeletesAreVisible(a: Int) = primitive(_.ownDeletesAreVisible(a))
    override def ownInsertsAreVisible(a: Int) = primitive(_.ownInsertsAreVisible(a))
    override def ownUpdatesAreVisible(a: Int) = primitive(_.ownUpdatesAreVisible(a))
    override def storesLowerCaseIdentifiers = primitive(_.storesLowerCaseIdentifiers)
    override def storesLowerCaseQuotedIdentifiers = primitive(_.storesLowerCaseQuotedIdentifiers)
    override def storesMixedCaseIdentifiers = primitive(_.storesMixedCaseIdentifiers)
    override def storesMixedCaseQuotedIdentifiers = primitive(_.storesMixedCaseQuotedIdentifiers)
    override def storesUpperCaseIdentifiers = primitive(_.storesUpperCaseIdentifiers)
    override def storesUpperCaseQuotedIdentifiers = primitive(_.storesUpperCaseQuotedIdentifiers)
    override def supportsANSI92EntryLevelSQL = primitive(_.supportsANSI92EntryLevelSQL)
    override def supportsANSI92FullSQL = primitive(_.supportsANSI92FullSQL)
    override def supportsANSI92IntermediateSQL = primitive(_.supportsANSI92IntermediateSQL)
    override def supportsAlterTableWithAddColumn = primitive(_.supportsAlterTableWithAddColumn)
    override def supportsAlterTableWithDropColumn = primitive(_.supportsAlterTableWithDropColumn)
    override def supportsBatchUpdates = primitive(_.supportsBatchUpdates)
    override def supportsCatalogsInDataManipulation = primitive(_.supportsCatalogsInDataManipulation)
    override def supportsCatalogsInIndexDefinitions = primitive(_.supportsCatalogsInIndexDefinitions)
    override def supportsCatalogsInPrivilegeDefinitions = primitive(_.supportsCatalogsInPrivilegeDefinitions)
    override def supportsCatalogsInProcedureCalls = primitive(_.supportsCatalogsInProcedureCalls)
    override def supportsCatalogsInTableDefinitions = primitive(_.supportsCatalogsInTableDefinitions)
    override def supportsColumnAliasing = primitive(_.supportsColumnAliasing)
    override def supportsConvert = primitive(_.supportsConvert)
    override def supportsConvert(a: Int, b: Int) = primitive(_.supportsConvert(a, b))
    override def supportsCoreSQLGrammar = primitive(_.supportsCoreSQLGrammar)
    override def supportsCorrelatedSubqueries = primitive(_.supportsCorrelatedSubqueries)
    override def supportsDataDefinitionAndDataManipulationTransactions = primitive(_.supportsDataDefinitionAndDataManipulationTransactions)
    override def supportsDataManipulationTransactionsOnly = primitive(_.supportsDataManipulationTransactionsOnly)
    override def supportsDifferentTableCorrelationNames = primitive(_.supportsDifferentTableCorrelationNames)
    override def supportsExpressionsInOrderBy = primitive(_.supportsExpressionsInOrderBy)
    override def supportsExtendedSQLGrammar = primitive(_.supportsExtendedSQLGrammar)
    override def supportsFullOuterJoins = primitive(_.supportsFullOuterJoins)
    override def supportsGetGeneratedKeys = primitive(_.supportsGetGeneratedKeys)
    override def supportsGroupBy = primitive(_.supportsGroupBy)
    override def supportsGroupByBeyondSelect = primitive(_.supportsGroupByBeyondSelect)
    override def supportsGroupByUnrelated = primitive(_.supportsGroupByUnrelated)
    override def supportsIntegrityEnhancementFacility = primitive(_.supportsIntegrityEnhancementFacility)
    override def supportsLikeEscapeClause = primitive(_.supportsLikeEscapeClause)
    override def supportsLimitedOuterJoins = primitive(_.supportsLimitedOuterJoins)
    override def supportsMinimumSQLGrammar = primitive(_.supportsMinimumSQLGrammar)
    override def supportsMixedCaseIdentifiers = primitive(_.supportsMixedCaseIdentifiers)
    override def supportsMixedCaseQuotedIdentifiers = primitive(_.supportsMixedCaseQuotedIdentifiers)
    override def supportsMultipleOpenResults = primitive(_.supportsMultipleOpenResults)
    override def supportsMultipleResultSets = primitive(_.supportsMultipleResultSets)
    override def supportsMultipleTransactions = primitive(_.supportsMultipleTransactions)
    override def supportsNamedParameters = primitive(_.supportsNamedParameters)
    override def supportsNonNullableColumns = primitive(_.supportsNonNullableColumns)
    override def supportsOpenCursorsAcrossCommit = primitive(_.supportsOpenCursorsAcrossCommit)
    override def supportsOpenCursorsAcrossRollback = primitive(_.supportsOpenCursorsAcrossRollback)
    override def supportsOpenStatementsAcrossCommit = primitive(_.supportsOpenStatementsAcrossCommit)
    override def supportsOpenStatementsAcrossRollback = primitive(_.supportsOpenStatementsAcrossRollback)
    override def supportsOrderByUnrelated = primitive(_.supportsOrderByUnrelated)
    override def supportsOuterJoins = primitive(_.supportsOuterJoins)
    override def supportsPositionedDelete = primitive(_.supportsPositionedDelete)
    override def supportsPositionedUpdate = primitive(_.supportsPositionedUpdate)
    override def supportsRefCursors = primitive(_.supportsRefCursors)
    override def supportsResultSetConcurrency(a: Int, b: Int) = primitive(_.supportsResultSetConcurrency(a, b))
    override def supportsResultSetHoldability(a: Int) = primitive(_.supportsResultSetHoldability(a))
    override def supportsResultSetType(a: Int) = primitive(_.supportsResultSetType(a))
    override def supportsSavepoints = primitive(_.supportsSavepoints)
    override def supportsSchemasInDataManipulation = primitive(_.supportsSchemasInDataManipulation)
    override def supportsSchemasInIndexDefinitions = primitive(_.supportsSchemasInIndexDefinitions)
    override def supportsSchemasInPrivilegeDefinitions = primitive(_.supportsSchemasInPrivilegeDefinitions)
    override def supportsSchemasInProcedureCalls = primitive(_.supportsSchemasInProcedureCalls)
    override def supportsSchemasInTableDefinitions = primitive(_.supportsSchemasInTableDefinitions)
    override def supportsSelectForUpdate = primitive(_.supportsSelectForUpdate)
    override def supportsStatementPooling = primitive(_.supportsStatementPooling)
    override def supportsStoredFunctionsUsingCallSyntax = primitive(_.supportsStoredFunctionsUsingCallSyntax)
    override def supportsStoredProcedures = primitive(_.supportsStoredProcedures)
    override def supportsSubqueriesInComparisons = primitive(_.supportsSubqueriesInComparisons)
    override def supportsSubqueriesInExists = primitive(_.supportsSubqueriesInExists)
    override def supportsSubqueriesInIns = primitive(_.supportsSubqueriesInIns)
    override def supportsSubqueriesInQuantifieds = primitive(_.supportsSubqueriesInQuantifieds)
    override def supportsTableCorrelationNames = primitive(_.supportsTableCorrelationNames)
    override def supportsTransactionIsolationLevel(a: Int) = primitive(_.supportsTransactionIsolationLevel(a))
    override def supportsTransactions = primitive(_.supportsTransactions)
    override def supportsUnion = primitive(_.supportsUnion)
    override def supportsUnionAll = primitive(_.supportsUnionAll)
    override def unwrap[T](a: Class[T]) = primitive(_.unwrap(a))
    override def updatesAreDetected(a: Int) = primitive(_.updatesAreDetected(a))
    override def usesLocalFilePerTable = primitive(_.usesLocalFilePerTable)
    override def usesLocalFiles = primitive(_.usesLocalFiles)

  }

  trait DriverInterpreter extends DriverOp.Visitor[Kleisli[M, Driver, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: Driver => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[Driver]
    override def realTime = outer.realTime[Driver]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[Driver]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using DriverIO we must call ourself recursively
    override def handleErrorWith[A](fa: DriverIO[A])(f: Throwable => DriverIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: DriverIO[A])(fb: DriverIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[DriverIO] => DriverIO[A]) = outer.uncancelable(this, doobie.free.driver.capturePoll)(body)
    override def poll[A](poll: Any, fa: DriverIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: DriverIO[A], fin: DriverIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: DriverIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def acceptsURL(a: String) = primitive(_.acceptsURL(a))
    override def connect(a: String, b: Properties) = primitive(_.connect(a, b))
    override def getMajorVersion = primitive(_.getMajorVersion)
    override def getMinorVersion = primitive(_.getMinorVersion)
    override def getParentLogger = primitive(_.getParentLogger)
    override def getPropertyInfo(a: String, b: Properties) = primitive(_.getPropertyInfo(a, b))
    override def jdbcCompliant = primitive(_.jdbcCompliant)

  }

  trait RefInterpreter extends RefOp.Visitor[Kleisli[M, Ref, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: Ref => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[Ref]
    override def realTime = outer.realTime[Ref]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[Ref]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using RefIO we must call ourself recursively
    override def handleErrorWith[A](fa: RefIO[A])(f: Throwable => RefIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: RefIO[A])(fb: RefIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[RefIO] => RefIO[A]) = outer.uncancelable(this, doobie.free.ref.capturePoll)(body)
    override def poll[A](poll: Any, fa: RefIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: RefIO[A], fin: RefIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: RefIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def getBaseTypeName = primitive(_.getBaseTypeName)
    override def getObject = primitive(_.getObject)
    override def getObject(a: Map[String, Class[_]]) = primitive(_.getObject(a))
    override def setObject(a: AnyRef) = primitive(_.setObject(a))

  }

  trait SQLDataInterpreter extends SQLDataOp.Visitor[Kleisli[M, SQLData, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: SQLData => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[SQLData]
    override def realTime = outer.realTime[SQLData]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[SQLData]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using SQLDataIO we must call ourself recursively
    override def handleErrorWith[A](fa: SQLDataIO[A])(f: Throwable => SQLDataIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: SQLDataIO[A])(fb: SQLDataIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[SQLDataIO] => SQLDataIO[A]) = outer.uncancelable(this, doobie.free.sqldata.capturePoll)(body)
    override def poll[A](poll: Any, fa: SQLDataIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: SQLDataIO[A], fin: SQLDataIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: SQLDataIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def getSQLTypeName = primitive(_.getSQLTypeName)
    override def readSQL(a: SQLInput, b: String) = primitive(_.readSQL(a, b))
    override def writeSQL(a: SQLOutput) = primitive(_.writeSQL(a))

  }

  trait SQLInputInterpreter extends SQLInputOp.Visitor[Kleisli[M, SQLInput, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: SQLInput => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[SQLInput]
    override def realTime = outer.realTime[SQLInput]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[SQLInput]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using SQLInputIO we must call ourself recursively
    override def handleErrorWith[A](fa: SQLInputIO[A])(f: Throwable => SQLInputIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: SQLInputIO[A])(fb: SQLInputIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[SQLInputIO] => SQLInputIO[A]) = outer.uncancelable(this, doobie.free.sqlinput.capturePoll)(body)
    override def poll[A](poll: Any, fa: SQLInputIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: SQLInputIO[A], fin: SQLInputIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: SQLInputIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def readArray = primitive(_.readArray)
    override def readAsciiStream = primitive(_.readAsciiStream)
    override def readBigDecimal = primitive(_.readBigDecimal)
    override def readBinaryStream = primitive(_.readBinaryStream)
    override def readBlob = primitive(_.readBlob)
    override def readBoolean = primitive(_.readBoolean)
    override def readByte = primitive(_.readByte)
    override def readBytes = primitive(_.readBytes)
    override def readCharacterStream = primitive(_.readCharacterStream)
    override def readClob = primitive(_.readClob)
    override def readDate = primitive(_.readDate)
    override def readDouble = primitive(_.readDouble)
    override def readFloat = primitive(_.readFloat)
    override def readInt = primitive(_.readInt)
    override def readLong = primitive(_.readLong)
    override def readNClob = primitive(_.readNClob)
    override def readNString = primitive(_.readNString)
    override def readObject = primitive(_.readObject)
    override def readObject[T](a: Class[T]) = primitive(_.readObject(a))
    override def readRef = primitive(_.readRef)
    override def readRowId = primitive(_.readRowId)
    override def readSQLXML = primitive(_.readSQLXML)
    override def readShort = primitive(_.readShort)
    override def readString = primitive(_.readString)
    override def readTime = primitive(_.readTime)
    override def readTimestamp = primitive(_.readTimestamp)
    override def readURL = primitive(_.readURL)
    override def wasNull = primitive(_.wasNull)

  }

  trait SQLOutputInterpreter extends SQLOutputOp.Visitor[Kleisli[M, SQLOutput, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: SQLOutput => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[SQLOutput]
    override def realTime = outer.realTime[SQLOutput]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[SQLOutput]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using SQLOutputIO we must call ourself recursively
    override def handleErrorWith[A](fa: SQLOutputIO[A])(f: Throwable => SQLOutputIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: SQLOutputIO[A])(fb: SQLOutputIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[SQLOutputIO] => SQLOutputIO[A]) = outer.uncancelable(this, doobie.free.sqloutput.capturePoll)(body)
    override def poll[A](poll: Any, fa: SQLOutputIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: SQLOutputIO[A], fin: SQLOutputIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: SQLOutputIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def writeArray(a: SqlArray) = primitive(_.writeArray(a))
    override def writeAsciiStream(a: InputStream) = primitive(_.writeAsciiStream(a))
    override def writeBigDecimal(a: BigDecimal) = primitive(_.writeBigDecimal(a))
    override def writeBinaryStream(a: InputStream) = primitive(_.writeBinaryStream(a))
    override def writeBlob(a: Blob) = primitive(_.writeBlob(a))
    override def writeBoolean(a: Boolean) = primitive(_.writeBoolean(a))
    override def writeByte(a: Byte) = primitive(_.writeByte(a))
    override def writeBytes(a: Array[Byte]) = primitive(_.writeBytes(a))
    override def writeCharacterStream(a: Reader) = primitive(_.writeCharacterStream(a))
    override def writeClob(a: Clob) = primitive(_.writeClob(a))
    override def writeDate(a: Date) = primitive(_.writeDate(a))
    override def writeDouble(a: Double) = primitive(_.writeDouble(a))
    override def writeFloat(a: Float) = primitive(_.writeFloat(a))
    override def writeInt(a: Int) = primitive(_.writeInt(a))
    override def writeLong(a: Long) = primitive(_.writeLong(a))
    override def writeNClob(a: NClob) = primitive(_.writeNClob(a))
    override def writeNString(a: String) = primitive(_.writeNString(a))
    override def writeObject(a: AnyRef, b: SQLType) = primitive(_.writeObject(a, b))
    override def writeObject(a: SQLData) = primitive(_.writeObject(a))
    override def writeRef(a: Ref) = primitive(_.writeRef(a))
    override def writeRowId(a: RowId) = primitive(_.writeRowId(a))
    override def writeSQLXML(a: SQLXML) = primitive(_.writeSQLXML(a))
    override def writeShort(a: Short) = primitive(_.writeShort(a))
    override def writeString(a: String) = primitive(_.writeString(a))
    override def writeStruct(a: Struct) = primitive(_.writeStruct(a))
    override def writeTime(a: Time) = primitive(_.writeTime(a))
    override def writeTimestamp(a: Timestamp) = primitive(_.writeTimestamp(a))
    override def writeURL(a: URL) = primitive(_.writeURL(a))

  }

  trait ConnectionInterpreter extends ConnectionOp.Visitor[Kleisli[M, Connection, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: Connection => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[Connection]
    override def realTime = outer.realTime[Connection]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[Connection]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using ConnectionIO we must call ourself recursively
    override def handleErrorWith[A](fa: ConnectionIO[A])(f: Throwable => ConnectionIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: ConnectionIO[A])(fb: ConnectionIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[ConnectionIO] => ConnectionIO[A]) = outer.uncancelable(this, doobie.free.connection.capturePoll)(body)
    override def poll[A](poll: Any, fa: ConnectionIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: ConnectionIO[A], fin: ConnectionIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: ConnectionIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def abort(a: Executor) = primitive(_.abort(a))
    override def clearWarnings = primitive(_.clearWarnings)
    override def close = primitive(_.close)
    override def commit = primitive(_.commit)
    override def createArrayOf(a: String, b: Array[AnyRef]) = primitive(_.createArrayOf(a, b))
    override def createBlob = primitive(_.createBlob)
    override def createClob = primitive(_.createClob)
    override def createNClob = primitive(_.createNClob)
    override def createSQLXML = primitive(_.createSQLXML)
    override def createStatement = primitive(_.createStatement)
    override def createStatement(a: Int, b: Int) = primitive(_.createStatement(a, b))
    override def createStatement(a: Int, b: Int, c: Int) = primitive(_.createStatement(a, b, c))
    override def createStruct(a: String, b: Array[AnyRef]) = primitive(_.createStruct(a, b))
    override def getAutoCommit = primitive(_.getAutoCommit)
    override def getCatalog = primitive(_.getCatalog)
    override def getClientInfo = primitive(_.getClientInfo)
    override def getClientInfo(a: String) = primitive(_.getClientInfo(a))
    override def getHoldability = primitive(_.getHoldability)
    override def getMetaData = primitive(_.getMetaData)
    override def getNetworkTimeout = primitive(_.getNetworkTimeout)
    override def getSchema = primitive(_.getSchema)
    override def getTransactionIsolation = primitive(_.getTransactionIsolation)
    override def getTypeMap = primitive(_.getTypeMap)
    override def getWarnings = primitive(_.getWarnings)
    override def isClosed = primitive(_.isClosed)
    override def isReadOnly = primitive(_.isReadOnly)
    override def isValid(a: Int) = primitive(_.isValid(a))
    override def isWrapperFor(a: Class[_]) = primitive(_.isWrapperFor(a))
    override def nativeSQL(a: String) = primitive(_.nativeSQL(a))
    override def prepareCall(a: String) = primitive(_.prepareCall(a))
    override def prepareCall(a: String, b: Int, c: Int) = primitive(_.prepareCall(a, b, c))
    override def prepareCall(a: String, b: Int, c: Int, d: Int) = primitive(_.prepareCall(a, b, c, d))
    override def prepareStatement(a: String) = primitive(_.prepareStatement(a))
    override def prepareStatement(a: String, b: Array[Int]) = primitive(_.prepareStatement(a, b))
    override def prepareStatement(a: String, b: Array[String]) = primitive(_.prepareStatement(a, b))
    override def prepareStatement(a: String, b: Int) = primitive(_.prepareStatement(a, b))
    override def prepareStatement(a: String, b: Int, c: Int) = primitive(_.prepareStatement(a, b, c))
    override def prepareStatement(a: String, b: Int, c: Int, d: Int) = primitive(_.prepareStatement(a, b, c, d))
    override def releaseSavepoint(a: Savepoint) = primitive(_.releaseSavepoint(a))
    override def rollback = primitive(_.rollback)
    override def rollback(a: Savepoint) = primitive(_.rollback(a))
    override def setAutoCommit(a: Boolean) = primitive(_.setAutoCommit(a))
    override def setCatalog(a: String) = primitive(_.setCatalog(a))
    override def setClientInfo(a: Properties) = primitive(_.setClientInfo(a))
    override def setClientInfo(a: String, b: String) = primitive(_.setClientInfo(a, b))
    override def setHoldability(a: Int) = primitive(_.setHoldability(a))
    override def setNetworkTimeout(a: Executor, b: Int) = primitive(_.setNetworkTimeout(a, b))
    override def setReadOnly(a: Boolean) = primitive(_.setReadOnly(a))
    override def setSavepoint = primitive(_.setSavepoint)
    override def setSavepoint(a: String) = primitive(_.setSavepoint(a))
    override def setSchema(a: String) = primitive(_.setSchema(a))
    override def setTransactionIsolation(a: Int) = primitive(_.setTransactionIsolation(a))
    override def setTypeMap(a: Map[String, Class[_]]) = primitive(_.setTypeMap(a))
    override def unwrap[T](a: Class[T]) = primitive(_.unwrap(a))

  }

  trait StatementInterpreter extends StatementOp.Visitor[Kleisli[M, Statement, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: Statement => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[Statement]
    override def realTime = outer.realTime[Statement]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[Statement]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using StatementIO we must call ourself recursively
    override def handleErrorWith[A](fa: StatementIO[A])(f: Throwable => StatementIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: StatementIO[A])(fb: StatementIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[StatementIO] => StatementIO[A]) = outer.uncancelable(this, doobie.free.statement.capturePoll)(body)
    override def poll[A](poll: Any, fa: StatementIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: StatementIO[A], fin: StatementIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: StatementIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def addBatch(a: String) = primitive(_.addBatch(a))
    override def cancel = primitive(_.cancel)
    override def clearBatch = primitive(_.clearBatch)
    override def clearWarnings = primitive(_.clearWarnings)
    override def close = primitive(_.close)
    override def closeOnCompletion = primitive(_.closeOnCompletion)
    override def execute(a: String) = primitive(_.execute(a))
    override def execute(a: String, b: Array[Int]) = primitive(_.execute(a, b))
    override def execute(a: String, b: Array[String]) = primitive(_.execute(a, b))
    override def execute(a: String, b: Int) = primitive(_.execute(a, b))
    override def executeBatch = primitive(_.executeBatch)
    override def executeLargeBatch = primitive(_.executeLargeBatch)
    override def executeLargeUpdate(a: String) = primitive(_.executeLargeUpdate(a))
    override def executeLargeUpdate(a: String, b: Array[Int]) = primitive(_.executeLargeUpdate(a, b))
    override def executeLargeUpdate(a: String, b: Array[String]) = primitive(_.executeLargeUpdate(a, b))
    override def executeLargeUpdate(a: String, b: Int) = primitive(_.executeLargeUpdate(a, b))
    override def executeQuery(a: String) = primitive(_.executeQuery(a))
    override def executeUpdate(a: String) = primitive(_.executeUpdate(a))
    override def executeUpdate(a: String, b: Array[Int]) = primitive(_.executeUpdate(a, b))
    override def executeUpdate(a: String, b: Array[String]) = primitive(_.executeUpdate(a, b))
    override def executeUpdate(a: String, b: Int) = primitive(_.executeUpdate(a, b))
    override def getConnection = primitive(_.getConnection)
    override def getFetchDirection = primitive(_.getFetchDirection)
    override def getFetchSize = primitive(_.getFetchSize)
    override def getGeneratedKeys = primitive(_.getGeneratedKeys)
    override def getLargeMaxRows = primitive(_.getLargeMaxRows)
    override def getLargeUpdateCount = primitive(_.getLargeUpdateCount)
    override def getMaxFieldSize = primitive(_.getMaxFieldSize)
    override def getMaxRows = primitive(_.getMaxRows)
    override def getMoreResults = primitive(_.getMoreResults)
    override def getMoreResults(a: Int) = primitive(_.getMoreResults(a))
    override def getQueryTimeout = primitive(_.getQueryTimeout)
    override def getResultSet = primitive(_.getResultSet)
    override def getResultSetConcurrency = primitive(_.getResultSetConcurrency)
    override def getResultSetHoldability = primitive(_.getResultSetHoldability)
    override def getResultSetType = primitive(_.getResultSetType)
    override def getUpdateCount = primitive(_.getUpdateCount)
    override def getWarnings = primitive(_.getWarnings)
    override def isCloseOnCompletion = primitive(_.isCloseOnCompletion)
    override def isClosed = primitive(_.isClosed)
    override def isPoolable = primitive(_.isPoolable)
    override def isWrapperFor(a: Class[_]) = primitive(_.isWrapperFor(a))
    override def setCursorName(a: String) = primitive(_.setCursorName(a))
    override def setEscapeProcessing(a: Boolean) = primitive(_.setEscapeProcessing(a))
    override def setFetchDirection(a: Int) = primitive(_.setFetchDirection(a))
    override def setFetchSize(a: Int) = primitive(_.setFetchSize(a))
    override def setLargeMaxRows(a: Long) = primitive(_.setLargeMaxRows(a))
    override def setMaxFieldSize(a: Int) = primitive(_.setMaxFieldSize(a))
    override def setMaxRows(a: Int) = primitive(_.setMaxRows(a))
    override def setPoolable(a: Boolean) = primitive(_.setPoolable(a))
    override def setQueryTimeout(a: Int) = primitive(_.setQueryTimeout(a))
    override def unwrap[T](a: Class[T]) = primitive(_.unwrap(a))

  }

  trait PreparedStatementInterpreter extends PreparedStatementOp.Visitor[Kleisli[M, PreparedStatement, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: PreparedStatement => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[PreparedStatement]
    override def realTime = outer.realTime[PreparedStatement]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[PreparedStatement]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using PreparedStatementIO we must call ourself recursively
    override def handleErrorWith[A](fa: PreparedStatementIO[A])(f: Throwable => PreparedStatementIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: PreparedStatementIO[A])(fb: PreparedStatementIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[PreparedStatementIO] => PreparedStatementIO[A]) = outer.uncancelable(this, doobie.free.preparedstatement.capturePoll)(body)
    override def poll[A](poll: Any, fa: PreparedStatementIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: PreparedStatementIO[A], fin: PreparedStatementIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: PreparedStatementIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def addBatch = primitive(_.addBatch)
    override def addBatch(a: String) = primitive(_.addBatch(a))
    override def cancel = primitive(_.cancel)
    override def clearBatch = primitive(_.clearBatch)
    override def clearParameters = primitive(_.clearParameters)
    override def clearWarnings = primitive(_.clearWarnings)
    override def close = primitive(_.close)
    override def closeOnCompletion = primitive(_.closeOnCompletion)
    override def execute = primitive(_.execute)
    override def execute(a: String) = primitive(_.execute(a))
    override def execute(a: String, b: Array[Int]) = primitive(_.execute(a, b))
    override def execute(a: String, b: Array[String]) = primitive(_.execute(a, b))
    override def execute(a: String, b: Int) = primitive(_.execute(a, b))
    override def executeBatch = primitive(_.executeBatch)
    override def executeLargeBatch = primitive(_.executeLargeBatch)
    override def executeLargeUpdate = primitive(_.executeLargeUpdate)
    override def executeLargeUpdate(a: String) = primitive(_.executeLargeUpdate(a))
    override def executeLargeUpdate(a: String, b: Array[Int]) = primitive(_.executeLargeUpdate(a, b))
    override def executeLargeUpdate(a: String, b: Array[String]) = primitive(_.executeLargeUpdate(a, b))
    override def executeLargeUpdate(a: String, b: Int) = primitive(_.executeLargeUpdate(a, b))
    override def executeQuery = primitive(_.executeQuery)
    override def executeQuery(a: String) = primitive(_.executeQuery(a))
    override def executeUpdate = primitive(_.executeUpdate)
    override def executeUpdate(a: String) = primitive(_.executeUpdate(a))
    override def executeUpdate(a: String, b: Array[Int]) = primitive(_.executeUpdate(a, b))
    override def executeUpdate(a: String, b: Array[String]) = primitive(_.executeUpdate(a, b))
    override def executeUpdate(a: String, b: Int) = primitive(_.executeUpdate(a, b))
    override def getConnection = primitive(_.getConnection)
    override def getFetchDirection = primitive(_.getFetchDirection)
    override def getFetchSize = primitive(_.getFetchSize)
    override def getGeneratedKeys = primitive(_.getGeneratedKeys)
    override def getLargeMaxRows = primitive(_.getLargeMaxRows)
    override def getLargeUpdateCount = primitive(_.getLargeUpdateCount)
    override def getMaxFieldSize = primitive(_.getMaxFieldSize)
    override def getMaxRows = primitive(_.getMaxRows)
    override def getMetaData = primitive(_.getMetaData)
    override def getMoreResults = primitive(_.getMoreResults)
    override def getMoreResults(a: Int) = primitive(_.getMoreResults(a))
    override def getParameterMetaData = primitive(_.getParameterMetaData)
    override def getQueryTimeout = primitive(_.getQueryTimeout)
    override def getResultSet = primitive(_.getResultSet)
    override def getResultSetConcurrency = primitive(_.getResultSetConcurrency)
    override def getResultSetHoldability = primitive(_.getResultSetHoldability)
    override def getResultSetType = primitive(_.getResultSetType)
    override def getUpdateCount = primitive(_.getUpdateCount)
    override def getWarnings = primitive(_.getWarnings)
    override def isCloseOnCompletion = primitive(_.isCloseOnCompletion)
    override def isClosed = primitive(_.isClosed)
    override def isPoolable = primitive(_.isPoolable)
    override def isWrapperFor(a: Class[_]) = primitive(_.isWrapperFor(a))
    override def setArray(a: Int, b: SqlArray) = primitive(_.setArray(a, b))
    override def setAsciiStream(a: Int, b: InputStream) = primitive(_.setAsciiStream(a, b))
    override def setAsciiStream(a: Int, b: InputStream, c: Int) = primitive(_.setAsciiStream(a, b, c))
    override def setAsciiStream(a: Int, b: InputStream, c: Long) = primitive(_.setAsciiStream(a, b, c))
    override def setBigDecimal(a: Int, b: BigDecimal) = primitive(_.setBigDecimal(a, b))
    override def setBinaryStream(a: Int, b: InputStream) = primitive(_.setBinaryStream(a, b))
    override def setBinaryStream(a: Int, b: InputStream, c: Int) = primitive(_.setBinaryStream(a, b, c))
    override def setBinaryStream(a: Int, b: InputStream, c: Long) = primitive(_.setBinaryStream(a, b, c))
    override def setBlob(a: Int, b: Blob) = primitive(_.setBlob(a, b))
    override def setBlob(a: Int, b: InputStream) = primitive(_.setBlob(a, b))
    override def setBlob(a: Int, b: InputStream, c: Long) = primitive(_.setBlob(a, b, c))
    override def setBoolean(a: Int, b: Boolean) = primitive(_.setBoolean(a, b))
    override def setByte(a: Int, b: Byte) = primitive(_.setByte(a, b))
    override def setBytes(a: Int, b: Array[Byte]) = primitive(_.setBytes(a, b))
    override def setCharacterStream(a: Int, b: Reader) = primitive(_.setCharacterStream(a, b))
    override def setCharacterStream(a: Int, b: Reader, c: Int) = primitive(_.setCharacterStream(a, b, c))
    override def setCharacterStream(a: Int, b: Reader, c: Long) = primitive(_.setCharacterStream(a, b, c))
    override def setClob(a: Int, b: Clob) = primitive(_.setClob(a, b))
    override def setClob(a: Int, b: Reader) = primitive(_.setClob(a, b))
    override def setClob(a: Int, b: Reader, c: Long) = primitive(_.setClob(a, b, c))
    override def setCursorName(a: String) = primitive(_.setCursorName(a))
    override def setDate(a: Int, b: Date) = primitive(_.setDate(a, b))
    override def setDate(a: Int, b: Date, c: Calendar) = primitive(_.setDate(a, b, c))
    override def setDouble(a: Int, b: Double) = primitive(_.setDouble(a, b))
    override def setEscapeProcessing(a: Boolean) = primitive(_.setEscapeProcessing(a))
    override def setFetchDirection(a: Int) = primitive(_.setFetchDirection(a))
    override def setFetchSize(a: Int) = primitive(_.setFetchSize(a))
    override def setFloat(a: Int, b: Float) = primitive(_.setFloat(a, b))
    override def setInt(a: Int, b: Int) = primitive(_.setInt(a, b))
    override def setLargeMaxRows(a: Long) = primitive(_.setLargeMaxRows(a))
    override def setLong(a: Int, b: Long) = primitive(_.setLong(a, b))
    override def setMaxFieldSize(a: Int) = primitive(_.setMaxFieldSize(a))
    override def setMaxRows(a: Int) = primitive(_.setMaxRows(a))
    override def setNCharacterStream(a: Int, b: Reader) = primitive(_.setNCharacterStream(a, b))
    override def setNCharacterStream(a: Int, b: Reader, c: Long) = primitive(_.setNCharacterStream(a, b, c))
    override def setNClob(a: Int, b: NClob) = primitive(_.setNClob(a, b))
    override def setNClob(a: Int, b: Reader) = primitive(_.setNClob(a, b))
    override def setNClob(a: Int, b: Reader, c: Long) = primitive(_.setNClob(a, b, c))
    override def setNString(a: Int, b: String) = primitive(_.setNString(a, b))
    override def setNull(a: Int, b: Int) = primitive(_.setNull(a, b))
    override def setNull(a: Int, b: Int, c: String) = primitive(_.setNull(a, b, c))
    override def setObject(a: Int, b: AnyRef) = primitive(_.setObject(a, b))
    override def setObject(a: Int, b: AnyRef, c: Int) = primitive(_.setObject(a, b, c))
    override def setObject(a: Int, b: AnyRef, c: Int, d: Int) = primitive(_.setObject(a, b, c, d))
    override def setObject(a: Int, b: AnyRef, c: SQLType) = primitive(_.setObject(a, b, c))
    override def setObject(a: Int, b: AnyRef, c: SQLType, d: Int) = primitive(_.setObject(a, b, c, d))
    override def setPoolable(a: Boolean) = primitive(_.setPoolable(a))
    override def setQueryTimeout(a: Int) = primitive(_.setQueryTimeout(a))
    override def setRef(a: Int, b: Ref) = primitive(_.setRef(a, b))
    override def setRowId(a: Int, b: RowId) = primitive(_.setRowId(a, b))
    override def setSQLXML(a: Int, b: SQLXML) = primitive(_.setSQLXML(a, b))
    override def setShort(a: Int, b: Short) = primitive(_.setShort(a, b))
    override def setString(a: Int, b: String) = primitive(_.setString(a, b))
    override def setTime(a: Int, b: Time) = primitive(_.setTime(a, b))
    override def setTime(a: Int, b: Time, c: Calendar) = primitive(_.setTime(a, b, c))
    override def setTimestamp(a: Int, b: Timestamp) = primitive(_.setTimestamp(a, b))
    override def setTimestamp(a: Int, b: Timestamp, c: Calendar) = primitive(_.setTimestamp(a, b, c))
    override def setURL(a: Int, b: URL) = primitive(_.setURL(a, b))
    override def unwrap[T](a: Class[T]) = primitive(_.unwrap(a))

  }

  trait CallableStatementInterpreter extends CallableStatementOp.Visitor[Kleisli[M, CallableStatement, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: CallableStatement => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[CallableStatement]
    override def realTime = outer.realTime[CallableStatement]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[CallableStatement]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using CallableStatementIO we must call ourself recursively
    override def handleErrorWith[A](fa: CallableStatementIO[A])(f: Throwable => CallableStatementIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: CallableStatementIO[A])(fb: CallableStatementIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[CallableStatementIO] => CallableStatementIO[A]) = outer.uncancelable(this, doobie.free.callablestatement.capturePoll)(body)
    override def poll[A](poll: Any, fa: CallableStatementIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: CallableStatementIO[A], fin: CallableStatementIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: CallableStatementIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def addBatch = primitive(_.addBatch)
    override def addBatch(a: String) = primitive(_.addBatch(a))
    override def cancel = primitive(_.cancel)
    override def clearBatch = primitive(_.clearBatch)
    override def clearParameters = primitive(_.clearParameters)
    override def clearWarnings = primitive(_.clearWarnings)
    override def close = primitive(_.close)
    override def closeOnCompletion = primitive(_.closeOnCompletion)
    override def execute = primitive(_.execute)
    override def execute(a: String) = primitive(_.execute(a))
    override def execute(a: String, b: Array[Int]) = primitive(_.execute(a, b))
    override def execute(a: String, b: Array[String]) = primitive(_.execute(a, b))
    override def execute(a: String, b: Int) = primitive(_.execute(a, b))
    override def executeBatch = primitive(_.executeBatch)
    override def executeLargeBatch = primitive(_.executeLargeBatch)
    override def executeLargeUpdate = primitive(_.executeLargeUpdate)
    override def executeLargeUpdate(a: String) = primitive(_.executeLargeUpdate(a))
    override def executeLargeUpdate(a: String, b: Array[Int]) = primitive(_.executeLargeUpdate(a, b))
    override def executeLargeUpdate(a: String, b: Array[String]) = primitive(_.executeLargeUpdate(a, b))
    override def executeLargeUpdate(a: String, b: Int) = primitive(_.executeLargeUpdate(a, b))
    override def executeQuery = primitive(_.executeQuery)
    override def executeQuery(a: String) = primitive(_.executeQuery(a))
    override def executeUpdate = primitive(_.executeUpdate)
    override def executeUpdate(a: String) = primitive(_.executeUpdate(a))
    override def executeUpdate(a: String, b: Array[Int]) = primitive(_.executeUpdate(a, b))
    override def executeUpdate(a: String, b: Array[String]) = primitive(_.executeUpdate(a, b))
    override def executeUpdate(a: String, b: Int) = primitive(_.executeUpdate(a, b))
    override def getArray(a: Int) = primitive(_.getArray(a))
    override def getArray(a: String) = primitive(_.getArray(a))
    override def getBigDecimal(a: Int) = primitive(_.getBigDecimal(a))
    override def getBigDecimal(a: String) = primitive(_.getBigDecimal(a))
    override def getBlob(a: Int) = primitive(_.getBlob(a))
    override def getBlob(a: String) = primitive(_.getBlob(a))
    override def getBoolean(a: Int) = primitive(_.getBoolean(a))
    override def getBoolean(a: String) = primitive(_.getBoolean(a))
    override def getByte(a: Int) = primitive(_.getByte(a))
    override def getByte(a: String) = primitive(_.getByte(a))
    override def getBytes(a: Int) = primitive(_.getBytes(a))
    override def getBytes(a: String) = primitive(_.getBytes(a))
    override def getCharacterStream(a: Int) = primitive(_.getCharacterStream(a))
    override def getCharacterStream(a: String) = primitive(_.getCharacterStream(a))
    override def getClob(a: Int) = primitive(_.getClob(a))
    override def getClob(a: String) = primitive(_.getClob(a))
    override def getConnection = primitive(_.getConnection)
    override def getDate(a: Int) = primitive(_.getDate(a))
    override def getDate(a: Int, b: Calendar) = primitive(_.getDate(a, b))
    override def getDate(a: String) = primitive(_.getDate(a))
    override def getDate(a: String, b: Calendar) = primitive(_.getDate(a, b))
    override def getDouble(a: Int) = primitive(_.getDouble(a))
    override def getDouble(a: String) = primitive(_.getDouble(a))
    override def getFetchDirection = primitive(_.getFetchDirection)
    override def getFetchSize = primitive(_.getFetchSize)
    override def getFloat(a: Int) = primitive(_.getFloat(a))
    override def getFloat(a: String) = primitive(_.getFloat(a))
    override def getGeneratedKeys = primitive(_.getGeneratedKeys)
    override def getInt(a: Int) = primitive(_.getInt(a))
    override def getInt(a: String) = primitive(_.getInt(a))
    override def getLargeMaxRows = primitive(_.getLargeMaxRows)
    override def getLargeUpdateCount = primitive(_.getLargeUpdateCount)
    override def getLong(a: Int) = primitive(_.getLong(a))
    override def getLong(a: String) = primitive(_.getLong(a))
    override def getMaxFieldSize = primitive(_.getMaxFieldSize)
    override def getMaxRows = primitive(_.getMaxRows)
    override def getMetaData = primitive(_.getMetaData)
    override def getMoreResults = primitive(_.getMoreResults)
    override def getMoreResults(a: Int) = primitive(_.getMoreResults(a))
    override def getNCharacterStream(a: Int) = primitive(_.getNCharacterStream(a))
    override def getNCharacterStream(a: String) = primitive(_.getNCharacterStream(a))
    override def getNClob(a: Int) = primitive(_.getNClob(a))
    override def getNClob(a: String) = primitive(_.getNClob(a))
    override def getNString(a: Int) = primitive(_.getNString(a))
    override def getNString(a: String) = primitive(_.getNString(a))
    override def getObject(a: Int) = primitive(_.getObject(a))
    override def getObject[T](a: Int, b: Class[T]) = primitive(_.getObject(a, b))
    override def getObject(a: Int, b: Map[String, Class[_]]) = primitive(_.getObject(a, b))
    override def getObject(a: String) = primitive(_.getObject(a))
    override def getObject[T](a: String, b: Class[T]) = primitive(_.getObject(a, b))
    override def getObject(a: String, b: Map[String, Class[_]]) = primitive(_.getObject(a, b))
    override def getParameterMetaData = primitive(_.getParameterMetaData)
    override def getQueryTimeout = primitive(_.getQueryTimeout)
    override def getRef(a: Int) = primitive(_.getRef(a))
    override def getRef(a: String) = primitive(_.getRef(a))
    override def getResultSet = primitive(_.getResultSet)
    override def getResultSetConcurrency = primitive(_.getResultSetConcurrency)
    override def getResultSetHoldability = primitive(_.getResultSetHoldability)
    override def getResultSetType = primitive(_.getResultSetType)
    override def getRowId(a: Int) = primitive(_.getRowId(a))
    override def getRowId(a: String) = primitive(_.getRowId(a))
    override def getSQLXML(a: Int) = primitive(_.getSQLXML(a))
    override def getSQLXML(a: String) = primitive(_.getSQLXML(a))
    override def getShort(a: Int) = primitive(_.getShort(a))
    override def getShort(a: String) = primitive(_.getShort(a))
    override def getString(a: Int) = primitive(_.getString(a))
    override def getString(a: String) = primitive(_.getString(a))
    override def getTime(a: Int) = primitive(_.getTime(a))
    override def getTime(a: Int, b: Calendar) = primitive(_.getTime(a, b))
    override def getTime(a: String) = primitive(_.getTime(a))
    override def getTime(a: String, b: Calendar) = primitive(_.getTime(a, b))
    override def getTimestamp(a: Int) = primitive(_.getTimestamp(a))
    override def getTimestamp(a: Int, b: Calendar) = primitive(_.getTimestamp(a, b))
    override def getTimestamp(a: String) = primitive(_.getTimestamp(a))
    override def getTimestamp(a: String, b: Calendar) = primitive(_.getTimestamp(a, b))
    override def getURL(a: Int) = primitive(_.getURL(a))
    override def getURL(a: String) = primitive(_.getURL(a))
    override def getUpdateCount = primitive(_.getUpdateCount)
    override def getWarnings = primitive(_.getWarnings)
    override def isCloseOnCompletion = primitive(_.isCloseOnCompletion)
    override def isClosed = primitive(_.isClosed)
    override def isPoolable = primitive(_.isPoolable)
    override def isWrapperFor(a: Class[_]) = primitive(_.isWrapperFor(a))
    override def registerOutParameter(a: Int, b: Int) = primitive(_.registerOutParameter(a, b))
    override def registerOutParameter(a: Int, b: Int, c: Int) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: Int, b: Int, c: String) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: Int, b: SQLType) = primitive(_.registerOutParameter(a, b))
    override def registerOutParameter(a: Int, b: SQLType, c: Int) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: Int, b: SQLType, c: String) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: String, b: Int) = primitive(_.registerOutParameter(a, b))
    override def registerOutParameter(a: String, b: Int, c: Int) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: String, b: Int, c: String) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: String, b: SQLType) = primitive(_.registerOutParameter(a, b))
    override def registerOutParameter(a: String, b: SQLType, c: Int) = primitive(_.registerOutParameter(a, b, c))
    override def registerOutParameter(a: String, b: SQLType, c: String) = primitive(_.registerOutParameter(a, b, c))
    override def setArray(a: Int, b: SqlArray) = primitive(_.setArray(a, b))
    override def setAsciiStream(a: Int, b: InputStream) = primitive(_.setAsciiStream(a, b))
    override def setAsciiStream(a: Int, b: InputStream, c: Int) = primitive(_.setAsciiStream(a, b, c))
    override def setAsciiStream(a: Int, b: InputStream, c: Long) = primitive(_.setAsciiStream(a, b, c))
    override def setAsciiStream(a: String, b: InputStream) = primitive(_.setAsciiStream(a, b))
    override def setAsciiStream(a: String, b: InputStream, c: Int) = primitive(_.setAsciiStream(a, b, c))
    override def setAsciiStream(a: String, b: InputStream, c: Long) = primitive(_.setAsciiStream(a, b, c))
    override def setBigDecimal(a: Int, b: BigDecimal) = primitive(_.setBigDecimal(a, b))
    override def setBigDecimal(a: String, b: BigDecimal) = primitive(_.setBigDecimal(a, b))
    override def setBinaryStream(a: Int, b: InputStream) = primitive(_.setBinaryStream(a, b))
    override def setBinaryStream(a: Int, b: InputStream, c: Int) = primitive(_.setBinaryStream(a, b, c))
    override def setBinaryStream(a: Int, b: InputStream, c: Long) = primitive(_.setBinaryStream(a, b, c))
    override def setBinaryStream(a: String, b: InputStream) = primitive(_.setBinaryStream(a, b))
    override def setBinaryStream(a: String, b: InputStream, c: Int) = primitive(_.setBinaryStream(a, b, c))
    override def setBinaryStream(a: String, b: InputStream, c: Long) = primitive(_.setBinaryStream(a, b, c))
    override def setBlob(a: Int, b: Blob) = primitive(_.setBlob(a, b))
    override def setBlob(a: Int, b: InputStream) = primitive(_.setBlob(a, b))
    override def setBlob(a: Int, b: InputStream, c: Long) = primitive(_.setBlob(a, b, c))
    override def setBlob(a: String, b: Blob) = primitive(_.setBlob(a, b))
    override def setBlob(a: String, b: InputStream) = primitive(_.setBlob(a, b))
    override def setBlob(a: String, b: InputStream, c: Long) = primitive(_.setBlob(a, b, c))
    override def setBoolean(a: Int, b: Boolean) = primitive(_.setBoolean(a, b))
    override def setBoolean(a: String, b: Boolean) = primitive(_.setBoolean(a, b))
    override def setByte(a: Int, b: Byte) = primitive(_.setByte(a, b))
    override def setByte(a: String, b: Byte) = primitive(_.setByte(a, b))
    override def setBytes(a: Int, b: Array[Byte]) = primitive(_.setBytes(a, b))
    override def setBytes(a: String, b: Array[Byte]) = primitive(_.setBytes(a, b))
    override def setCharacterStream(a: Int, b: Reader) = primitive(_.setCharacterStream(a, b))
    override def setCharacterStream(a: Int, b: Reader, c: Int) = primitive(_.setCharacterStream(a, b, c))
    override def setCharacterStream(a: Int, b: Reader, c: Long) = primitive(_.setCharacterStream(a, b, c))
    override def setCharacterStream(a: String, b: Reader) = primitive(_.setCharacterStream(a, b))
    override def setCharacterStream(a: String, b: Reader, c: Int) = primitive(_.setCharacterStream(a, b, c))
    override def setCharacterStream(a: String, b: Reader, c: Long) = primitive(_.setCharacterStream(a, b, c))
    override def setClob(a: Int, b: Clob) = primitive(_.setClob(a, b))
    override def setClob(a: Int, b: Reader) = primitive(_.setClob(a, b))
    override def setClob(a: Int, b: Reader, c: Long) = primitive(_.setClob(a, b, c))
    override def setClob(a: String, b: Clob) = primitive(_.setClob(a, b))
    override def setClob(a: String, b: Reader) = primitive(_.setClob(a, b))
    override def setClob(a: String, b: Reader, c: Long) = primitive(_.setClob(a, b, c))
    override def setCursorName(a: String) = primitive(_.setCursorName(a))
    override def setDate(a: Int, b: Date) = primitive(_.setDate(a, b))
    override def setDate(a: Int, b: Date, c: Calendar) = primitive(_.setDate(a, b, c))
    override def setDate(a: String, b: Date) = primitive(_.setDate(a, b))
    override def setDate(a: String, b: Date, c: Calendar) = primitive(_.setDate(a, b, c))
    override def setDouble(a: Int, b: Double) = primitive(_.setDouble(a, b))
    override def setDouble(a: String, b: Double) = primitive(_.setDouble(a, b))
    override def setEscapeProcessing(a: Boolean) = primitive(_.setEscapeProcessing(a))
    override def setFetchDirection(a: Int) = primitive(_.setFetchDirection(a))
    override def setFetchSize(a: Int) = primitive(_.setFetchSize(a))
    override def setFloat(a: Int, b: Float) = primitive(_.setFloat(a, b))
    override def setFloat(a: String, b: Float) = primitive(_.setFloat(a, b))
    override def setInt(a: Int, b: Int) = primitive(_.setInt(a, b))
    override def setInt(a: String, b: Int) = primitive(_.setInt(a, b))
    override def setLargeMaxRows(a: Long) = primitive(_.setLargeMaxRows(a))
    override def setLong(a: Int, b: Long) = primitive(_.setLong(a, b))
    override def setLong(a: String, b: Long) = primitive(_.setLong(a, b))
    override def setMaxFieldSize(a: Int) = primitive(_.setMaxFieldSize(a))
    override def setMaxRows(a: Int) = primitive(_.setMaxRows(a))
    override def setNCharacterStream(a: Int, b: Reader) = primitive(_.setNCharacterStream(a, b))
    override def setNCharacterStream(a: Int, b: Reader, c: Long) = primitive(_.setNCharacterStream(a, b, c))
    override def setNCharacterStream(a: String, b: Reader) = primitive(_.setNCharacterStream(a, b))
    override def setNCharacterStream(a: String, b: Reader, c: Long) = primitive(_.setNCharacterStream(a, b, c))
    override def setNClob(a: Int, b: NClob) = primitive(_.setNClob(a, b))
    override def setNClob(a: Int, b: Reader) = primitive(_.setNClob(a, b))
    override def setNClob(a: Int, b: Reader, c: Long) = primitive(_.setNClob(a, b, c))
    override def setNClob(a: String, b: NClob) = primitive(_.setNClob(a, b))
    override def setNClob(a: String, b: Reader) = primitive(_.setNClob(a, b))
    override def setNClob(a: String, b: Reader, c: Long) = primitive(_.setNClob(a, b, c))
    override def setNString(a: Int, b: String) = primitive(_.setNString(a, b))
    override def setNString(a: String, b: String) = primitive(_.setNString(a, b))
    override def setNull(a: Int, b: Int) = primitive(_.setNull(a, b))
    override def setNull(a: Int, b: Int, c: String) = primitive(_.setNull(a, b, c))
    override def setNull(a: String, b: Int) = primitive(_.setNull(a, b))
    override def setNull(a: String, b: Int, c: String) = primitive(_.setNull(a, b, c))
    override def setObject(a: Int, b: AnyRef) = primitive(_.setObject(a, b))
    override def setObject(a: Int, b: AnyRef, c: Int) = primitive(_.setObject(a, b, c))
    override def setObject(a: Int, b: AnyRef, c: Int, d: Int) = primitive(_.setObject(a, b, c, d))
    override def setObject(a: Int, b: AnyRef, c: SQLType) = primitive(_.setObject(a, b, c))
    override def setObject(a: Int, b: AnyRef, c: SQLType, d: Int) = primitive(_.setObject(a, b, c, d))
    override def setObject(a: String, b: AnyRef) = primitive(_.setObject(a, b))
    override def setObject(a: String, b: AnyRef, c: Int) = primitive(_.setObject(a, b, c))
    override def setObject(a: String, b: AnyRef, c: Int, d: Int) = primitive(_.setObject(a, b, c, d))
    override def setObject(a: String, b: AnyRef, c: SQLType) = primitive(_.setObject(a, b, c))
    override def setObject(a: String, b: AnyRef, c: SQLType, d: Int) = primitive(_.setObject(a, b, c, d))
    override def setPoolable(a: Boolean) = primitive(_.setPoolable(a))
    override def setQueryTimeout(a: Int) = primitive(_.setQueryTimeout(a))
    override def setRef(a: Int, b: Ref) = primitive(_.setRef(a, b))
    override def setRowId(a: Int, b: RowId) = primitive(_.setRowId(a, b))
    override def setRowId(a: String, b: RowId) = primitive(_.setRowId(a, b))
    override def setSQLXML(a: Int, b: SQLXML) = primitive(_.setSQLXML(a, b))
    override def setSQLXML(a: String, b: SQLXML) = primitive(_.setSQLXML(a, b))
    override def setShort(a: Int, b: Short) = primitive(_.setShort(a, b))
    override def setShort(a: String, b: Short) = primitive(_.setShort(a, b))
    override def setString(a: Int, b: String) = primitive(_.setString(a, b))
    override def setString(a: String, b: String) = primitive(_.setString(a, b))
    override def setTime(a: Int, b: Time) = primitive(_.setTime(a, b))
    override def setTime(a: Int, b: Time, c: Calendar) = primitive(_.setTime(a, b, c))
    override def setTime(a: String, b: Time) = primitive(_.setTime(a, b))
    override def setTime(a: String, b: Time, c: Calendar) = primitive(_.setTime(a, b, c))
    override def setTimestamp(a: Int, b: Timestamp) = primitive(_.setTimestamp(a, b))
    override def setTimestamp(a: Int, b: Timestamp, c: Calendar) = primitive(_.setTimestamp(a, b, c))
    override def setTimestamp(a: String, b: Timestamp) = primitive(_.setTimestamp(a, b))
    override def setTimestamp(a: String, b: Timestamp, c: Calendar) = primitive(_.setTimestamp(a, b, c))
    override def setURL(a: Int, b: URL) = primitive(_.setURL(a, b))
    override def setURL(a: String, b: URL) = primitive(_.setURL(a, b))
    override def unwrap[T](a: Class[T]) = primitive(_.unwrap(a))
    override def wasNull = primitive(_.wasNull)

  }

  trait ResultSetInterpreter extends ResultSetOp.Visitor[Kleisli[M, ResultSet, *]] {

    // common operations delegate to outer interpreter
    override def raw[A](f: ResultSet => A) = outer.raw(f)
    override def embed[A](e: Embedded[A]) = outer.embed(e)
    override def raiseError[A](e: Throwable) = outer.raiseError(e)
    override def monotonic = outer.monotonic[ResultSet]
    override def realTime = outer.realTime[ResultSet]
    override def delay[A](thunk: => A) = outer.delay(thunk)
    override def suspend[A](hint: Sync.Type)(thunk: => A) = outer.suspend(hint)(thunk)
    override def canceled = outer.canceled[ResultSet]

    override def performLogging(event: LogEvent) = Kleisli(_ => logHandler.run(event))

    // for operations using ResultSetIO we must call ourself recursively
    override def handleErrorWith[A](fa: ResultSetIO[A])(f: Throwable => ResultSetIO[A]) = outer.handleErrorWith(this)(fa)(f)
    override def forceR[A, B](fa: ResultSetIO[A])(fb: ResultSetIO[B]) = outer.forceR(this)(fa)(fb)
    override def uncancelable[A](body: Poll[ResultSetIO] => ResultSetIO[A]) = outer.uncancelable(this, doobie.free.resultset.capturePoll)(body)
    override def poll[A](poll: Any, fa: ResultSetIO[A]) = outer.poll(this)(poll, fa)
    override def onCancel[A](fa: ResultSetIO[A], fin: ResultSetIO[Unit]) = outer.onCancel(this)(fa, fin)
    override def fromFuture[A](fut: ResultSetIO[Future[A]]) = outer.fromFuture(this)(fut)

    // domain-specific operations are implemented in terms of `primitive`
    override def absolute(a: Int) = primitive(_.absolute(a))
    override def afterLast = primitive(_.afterLast)
    override def beforeFirst = primitive(_.beforeFirst)
    override def cancelRowUpdates = primitive(_.cancelRowUpdates)
    override def clearWarnings = primitive(_.clearWarnings)
    override def close = primitive(_.close)
    override def deleteRow = primitive(_.deleteRow)
    override def findColumn(a: String) = primitive(_.findColumn(a))
    override def first = primitive(_.first)
    override def getArray(a: Int) = primitive(_.getArray(a))
    override def getArray(a: String) = primitive(_.getArray(a))
    override def getAsciiStream(a: Int) = primitive(_.getAsciiStream(a))
    override def getAsciiStream(a: String) = primitive(_.getAsciiStream(a))
    override def getBigDecimal(a: Int) = primitive(_.getBigDecimal(a))
    override def getBigDecimal(a: String) = primitive(_.getBigDecimal(a))
    override def getBinaryStream(a: Int) = primitive(_.getBinaryStream(a))
    override def getBinaryStream(a: String) = primitive(_.getBinaryStream(a))
    override def getBlob(a: Int) = primitive(_.getBlob(a))
    override def getBlob(a: String) = primitive(_.getBlob(a))
    override def getBoolean(a: Int) = primitive(_.getBoolean(a))
    override def getBoolean(a: String) = primitive(_.getBoolean(a))
    override def getByte(a: Int) = primitive(_.getByte(a))
    override def getByte(a: String) = primitive(_.getByte(a))
    override def getBytes(a: Int) = primitive(_.getBytes(a))
    override def getBytes(a: String) = primitive(_.getBytes(a))
    override def getCharacterStream(a: Int) = primitive(_.getCharacterStream(a))
    override def getCharacterStream(a: String) = primitive(_.getCharacterStream(a))
    override def getClob(a: Int) = primitive(_.getClob(a))
    override def getClob(a: String) = primitive(_.getClob(a))
    override def getConcurrency = primitive(_.getConcurrency)
    override def getCursorName = primitive(_.getCursorName)
    override def getDate(a: Int) = primitive(_.getDate(a))
    override def getDate(a: Int, b: Calendar) = primitive(_.getDate(a, b))
    override def getDate(a: String) = primitive(_.getDate(a))
    override def getDate(a: String, b: Calendar) = primitive(_.getDate(a, b))
    override def getDouble(a: Int) = primitive(_.getDouble(a))
    override def getDouble(a: String) = primitive(_.getDouble(a))
    override def getFetchDirection = primitive(_.getFetchDirection)
    override def getFetchSize = primitive(_.getFetchSize)
    override def getFloat(a: Int) = primitive(_.getFloat(a))
    override def getFloat(a: String) = primitive(_.getFloat(a))
    override def getHoldability = primitive(_.getHoldability)
    override def getInt(a: Int) = primitive(_.getInt(a))
    override def getInt(a: String) = primitive(_.getInt(a))
    override def getLong(a: Int) = primitive(_.getLong(a))
    override def getLong(a: String) = primitive(_.getLong(a))
    override def getMetaData = primitive(_.getMetaData)
    override def getNCharacterStream(a: Int) = primitive(_.getNCharacterStream(a))
    override def getNCharacterStream(a: String) = primitive(_.getNCharacterStream(a))
    override def getNClob(a: Int) = primitive(_.getNClob(a))
    override def getNClob(a: String) = primitive(_.getNClob(a))
    override def getNString(a: Int) = primitive(_.getNString(a))
    override def getNString(a: String) = primitive(_.getNString(a))
    override def getObject(a: Int) = primitive(_.getObject(a))
    override def getObject[T](a: Int, b: Class[T]) = primitive(_.getObject(a, b))
    override def getObject(a: Int, b: Map[String, Class[_]]) = primitive(_.getObject(a, b))
    override def getObject(a: String) = primitive(_.getObject(a))
    override def getObject[T](a: String, b: Class[T]) = primitive(_.getObject(a, b))
    override def getObject(a: String, b: Map[String, Class[_]]) = primitive(_.getObject(a, b))
    override def getRef(a: Int) = primitive(_.getRef(a))
    override def getRef(a: String) = primitive(_.getRef(a))
    override def getRow = primitive(_.getRow)
    override def getRowId(a: Int) = primitive(_.getRowId(a))
    override def getRowId(a: String) = primitive(_.getRowId(a))
    override def getSQLXML(a: Int) = primitive(_.getSQLXML(a))
    override def getSQLXML(a: String) = primitive(_.getSQLXML(a))
    override def getShort(a: Int) = primitive(_.getShort(a))
    override def getShort(a: String) = primitive(_.getShort(a))
    override def getStatement = primitive(_.getStatement)
    override def getString(a: Int) = primitive(_.getString(a))
    override def getString(a: String) = primitive(_.getString(a))
    override def getTime(a: Int) = primitive(_.getTime(a))
    override def getTime(a: Int, b: Calendar) = primitive(_.getTime(a, b))
    override def getTime(a: String) = primitive(_.getTime(a))
    override def getTime(a: String, b: Calendar) = primitive(_.getTime(a, b))
    override def getTimestamp(a: Int) = primitive(_.getTimestamp(a))
    override def getTimestamp(a: Int, b: Calendar) = primitive(_.getTimestamp(a, b))
    override def getTimestamp(a: String) = primitive(_.getTimestamp(a))
    override def getTimestamp(a: String, b: Calendar) = primitive(_.getTimestamp(a, b))
    override def getType = primitive(_.getType)
    override def getURL(a: Int) = primitive(_.getURL(a))
    override def getURL(a: String) = primitive(_.getURL(a))
    override def getWarnings = primitive(_.getWarnings)
    override def insertRow = primitive(_.insertRow)
    override def isAfterLast = primitive(_.isAfterLast)
    override def isBeforeFirst = primitive(_.isBeforeFirst)
    override def isClosed = primitive(_.isClosed)
    override def isFirst = primitive(_.isFirst)
    override def isLast = primitive(_.isLast)
    override def isWrapperFor(a: Class[_]) = primitive(_.isWrapperFor(a))
    override def last = primitive(_.last)
    override def moveToCurrentRow = primitive(_.moveToCurrentRow)
    override def moveToInsertRow = primitive(_.moveToInsertRow)
    override def next = primitive(_.next)
    override def previous = primitive(_.previous)
    override def refreshRow = primitive(_.refreshRow)
    override def relative(a: Int) = primitive(_.relative(a))
    override def rowDeleted = primitive(_.rowDeleted)
    override def rowInserted = primitive(_.rowInserted)
    override def rowUpdated = primitive(_.rowUpdated)
    override def setFetchDirection(a: Int) = primitive(_.setFetchDirection(a))
    override def setFetchSize(a: Int) = primitive(_.setFetchSize(a))
    override def unwrap[T](a: Class[T]) = primitive(_.unwrap(a))
    override def updateArray(a: Int, b: SqlArray) = primitive(_.updateArray(a, b))
    override def updateArray(a: String, b: SqlArray) = primitive(_.updateArray(a, b))
    override def updateAsciiStream(a: Int, b: InputStream) = primitive(_.updateAsciiStream(a, b))
    override def updateAsciiStream(a: Int, b: InputStream, c: Int) = primitive(_.updateAsciiStream(a, b, c))
    override def updateAsciiStream(a: Int, b: InputStream, c: Long) = primitive(_.updateAsciiStream(a, b, c))
    override def updateAsciiStream(a: String, b: InputStream) = primitive(_.updateAsciiStream(a, b))
    override def updateAsciiStream(a: String, b: InputStream, c: Int) = primitive(_.updateAsciiStream(a, b, c))
    override def updateAsciiStream(a: String, b: InputStream, c: Long) = primitive(_.updateAsciiStream(a, b, c))
    override def updateBigDecimal(a: Int, b: BigDecimal) = primitive(_.updateBigDecimal(a, b))
    override def updateBigDecimal(a: String, b: BigDecimal) = primitive(_.updateBigDecimal(a, b))
    override def updateBinaryStream(a: Int, b: InputStream) = primitive(_.updateBinaryStream(a, b))
    override def updateBinaryStream(a: Int, b: InputStream, c: Int) = primitive(_.updateBinaryStream(a, b, c))
    override def updateBinaryStream(a: Int, b: InputStream, c: Long) = primitive(_.updateBinaryStream(a, b, c))
    override def updateBinaryStream(a: String, b: InputStream) = primitive(_.updateBinaryStream(a, b))
    override def updateBinaryStream(a: String, b: InputStream, c: Int) = primitive(_.updateBinaryStream(a, b, c))
    override def updateBinaryStream(a: String, b: InputStream, c: Long) = primitive(_.updateBinaryStream(a, b, c))
    override def updateBlob(a: Int, b: Blob) = primitive(_.updateBlob(a, b))
    override def updateBlob(a: Int, b: InputStream) = primitive(_.updateBlob(a, b))
    override def updateBlob(a: Int, b: InputStream, c: Long) = primitive(_.updateBlob(a, b, c))
    override def updateBlob(a: String, b: Blob) = primitive(_.updateBlob(a, b))
    override def updateBlob(a: String, b: InputStream) = primitive(_.updateBlob(a, b))
    override def updateBlob(a: String, b: InputStream, c: Long) = primitive(_.updateBlob(a, b, c))
    override def updateBoolean(a: Int, b: Boolean) = primitive(_.updateBoolean(a, b))
    override def updateBoolean(a: String, b: Boolean) = primitive(_.updateBoolean(a, b))
    override def updateByte(a: Int, b: Byte) = primitive(_.updateByte(a, b))
    override def updateByte(a: String, b: Byte) = primitive(_.updateByte(a, b))
    override def updateBytes(a: Int, b: Array[Byte]) = primitive(_.updateBytes(a, b))
    override def updateBytes(a: String, b: Array[Byte]) = primitive(_.updateBytes(a, b))
    override def updateCharacterStream(a: Int, b: Reader) = primitive(_.updateCharacterStream(a, b))
    override def updateCharacterStream(a: Int, b: Reader, c: Int) = primitive(_.updateCharacterStream(a, b, c))
    override def updateCharacterStream(a: Int, b: Reader, c: Long) = primitive(_.updateCharacterStream(a, b, c))
    override def updateCharacterStream(a: String, b: Reader) = primitive(_.updateCharacterStream(a, b))
    override def updateCharacterStream(a: String, b: Reader, c: Int) = primitive(_.updateCharacterStream(a, b, c))
    override def updateCharacterStream(a: String, b: Reader, c: Long) = primitive(_.updateCharacterStream(a, b, c))
    override def updateClob(a: Int, b: Clob) = primitive(_.updateClob(a, b))
    override def updateClob(a: Int, b: Reader) = primitive(_.updateClob(a, b))
    override def updateClob(a: Int, b: Reader, c: Long) = primitive(_.updateClob(a, b, c))
    override def updateClob(a: String, b: Clob) = primitive(_.updateClob(a, b))
    override def updateClob(a: String, b: Reader) = primitive(_.updateClob(a, b))
    override def updateClob(a: String, b: Reader, c: Long) = primitive(_.updateClob(a, b, c))
    override def updateDate(a: Int, b: Date) = primitive(_.updateDate(a, b))
    override def updateDate(a: String, b: Date) = primitive(_.updateDate(a, b))
    override def updateDouble(a: Int, b: Double) = primitive(_.updateDouble(a, b))
    override def updateDouble(a: String, b: Double) = primitive(_.updateDouble(a, b))
    override def updateFloat(a: Int, b: Float) = primitive(_.updateFloat(a, b))
    override def updateFloat(a: String, b: Float) = primitive(_.updateFloat(a, b))
    override def updateInt(a: Int, b: Int) = primitive(_.updateInt(a, b))
    override def updateInt(a: String, b: Int) = primitive(_.updateInt(a, b))
    override def updateLong(a: Int, b: Long) = primitive(_.updateLong(a, b))
    override def updateLong(a: String, b: Long) = primitive(_.updateLong(a, b))
    override def updateNCharacterStream(a: Int, b: Reader) = primitive(_.updateNCharacterStream(a, b))
    override def updateNCharacterStream(a: Int, b: Reader, c: Long) = primitive(_.updateNCharacterStream(a, b, c))
    override def updateNCharacterStream(a: String, b: Reader) = primitive(_.updateNCharacterStream(a, b))
    override def updateNCharacterStream(a: String, b: Reader, c: Long) = primitive(_.updateNCharacterStream(a, b, c))
    override def updateNClob(a: Int, b: NClob) = primitive(_.updateNClob(a, b))
    override def updateNClob(a: Int, b: Reader) = primitive(_.updateNClob(a, b))
    override def updateNClob(a: Int, b: Reader, c: Long) = primitive(_.updateNClob(a, b, c))
    override def updateNClob(a: String, b: NClob) = primitive(_.updateNClob(a, b))
    override def updateNClob(a: String, b: Reader) = primitive(_.updateNClob(a, b))
    override def updateNClob(a: String, b: Reader, c: Long) = primitive(_.updateNClob(a, b, c))
    override def updateNString(a: Int, b: String) = primitive(_.updateNString(a, b))
    override def updateNString(a: String, b: String) = primitive(_.updateNString(a, b))
    override def updateNull(a: Int) = primitive(_.updateNull(a))
    override def updateNull(a: String) = primitive(_.updateNull(a))
    override def updateObject(a: Int, b: AnyRef) = primitive(_.updateObject(a, b))
    override def updateObject(a: Int, b: AnyRef, c: Int) = primitive(_.updateObject(a, b, c))
    override def updateObject(a: Int, b: AnyRef, c: SQLType) = primitive(_.updateObject(a, b, c))
    override def updateObject(a: Int, b: AnyRef, c: SQLType, d: Int) = primitive(_.updateObject(a, b, c, d))
    override def updateObject(a: String, b: AnyRef) = primitive(_.updateObject(a, b))
    override def updateObject(a: String, b: AnyRef, c: Int) = primitive(_.updateObject(a, b, c))
    override def updateObject(a: String, b: AnyRef, c: SQLType) = primitive(_.updateObject(a, b, c))
    override def updateObject(a: String, b: AnyRef, c: SQLType, d: Int) = primitive(_.updateObject(a, b, c, d))
    override def updateRef(a: Int, b: Ref) = primitive(_.updateRef(a, b))
    override def updateRef(a: String, b: Ref) = primitive(_.updateRef(a, b))
    override def updateRow = primitive(_.updateRow)
    override def updateRowId(a: Int, b: RowId) = primitive(_.updateRowId(a, b))
    override def updateRowId(a: String, b: RowId) = primitive(_.updateRowId(a, b))
    override def updateSQLXML(a: Int, b: SQLXML) = primitive(_.updateSQLXML(a, b))
    override def updateSQLXML(a: String, b: SQLXML) = primitive(_.updateSQLXML(a, b))
    override def updateShort(a: Int, b: Short) = primitive(_.updateShort(a, b))
    override def updateShort(a: String, b: Short) = primitive(_.updateShort(a, b))
    override def updateString(a: Int, b: String) = primitive(_.updateString(a, b))
    override def updateString(a: String, b: String) = primitive(_.updateString(a, b))
    override def updateTime(a: Int, b: Time) = primitive(_.updateTime(a, b))
    override def updateTime(a: String, b: Time) = primitive(_.updateTime(a, b))
    override def updateTimestamp(a: Int, b: Timestamp) = primitive(_.updateTimestamp(a, b))
    override def updateTimestamp(a: String, b: Timestamp) = primitive(_.updateTimestamp(a, b))
    override def wasNull = primitive(_.wasNull)

  }


}

