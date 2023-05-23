package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.{StaticQuery => Q}

import scala.io.{BufferedSource, Codec, Source}

object SqlScriptRunner {
  def runScripts(filenames: Seq[String]) {
    executeStatements(filenames.flatMap(readScriptStatements("./viite-backend/digiroad2-oracle/sql/", _)))
  }

  def runViiteScripts(filenames: Seq[String]) {
    executeStatements(filenames.flatMap(readScriptStatements("./viite-backend/digiroad2-viite/sql/", _)))
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

  def executeStatements(stmts: Seq[String]) {
    println("Running " + stmts.length + " statements...")
    var i = 0
    PostGISDatabase.withDynTransaction {
      stmts.foreach { stmt =>
        try {
          (Q.u + stmt).execute
          i = i+1
          if (i % 10 == 0)
            println("" + i + " / " + stmts.length)
        } catch {
          case e: Exception => {
            e.printStackTrace
            println("failed statement: " + stmt.replaceAll("\\)", ")\n"))
            println("CONTINUING WITH NEXT STATEMENT...")
            return
          }
        }
      }
      println("DONE!")
    }
  }

  def executeStatement(statement: String) = executeStatements(List(statement))

  def executeStatements(sqls: String): Unit = {
    val statements = sqls.split(";")
    executeStatements(statements)
  }

}