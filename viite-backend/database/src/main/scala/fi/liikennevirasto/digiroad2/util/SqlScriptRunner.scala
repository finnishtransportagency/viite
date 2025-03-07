package fi.liikennevirasto.digiroad2.util

import fi.vaylavirasto.viite.dao.BaseDAO
import scalikejdbc._
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC

import scala.io.{BufferedSource, Codec, Source}

object SqlScriptRunner extends BaseDAO {
  def runScripts(filenames: Seq[String]): Unit = {
    executeStatements(filenames.flatMap(readScriptStatements("./viite-backend/database/sql/", _)))
  }

  def runViiteScripts(filenames: Seq[String]): Unit = {
    executeStatements(filenames.flatMap(readScriptStatements("./viite-backend/viite-main/sql/", _)))
  }

  def runScriptInClasspath(filename: String): Unit = {
    val src: BufferedSource = Source.fromInputStream(getClass.getResourceAsStream(filename))
    val statements = readScriptStatements(src)
    executeStatements(statements)
  }

  def readScriptStatements(path: String, filename: String): Seq[String] = {
    val source: BufferedSource = Source.fromFile(path + filename)(Codec.UTF8)
    readScriptStatements(source)
  }

  def readScriptStatements(source: BufferedSource): Seq[String] = {
    val commentR = """\/\*.*\*\/""".r
    val withComments = source.getLines.filterNot(_.trim.startsWith("--")).mkString
    commentR.replaceAllIn(withComments, "").split(";")
  }

  def executeStatements(stmts: Seq[String]): Unit = {
    println("Running " + stmts.length + " statements...")
    var i = 0
    PostGISDatabaseScalikeJDBC.runWithTransaction {
      stmts.foreach { stmt =>
        try {
          runUpdateToDb(SQL(stmt))
          i = i+1
          if (i % 10 == 0)
            println("" + i + " / " + stmts.length)
        } catch {
          case e: Exception =>
            e.printStackTrace
            println("failed statement: " + stmt.replaceAll("\\)", ")\n"))
            println("CONTINUING WITH NEXT STATEMENT...")
            return
        }
      }
      println("DONE!")
    }
  }

  def executeStatement(statement: String): Unit = executeStatements(List(statement))

  def executeStatements(sqls: String): Unit = {
    val statements = sqls.split(";")
    executeStatements(statements)
  }

}
