package fi.vaylavirasto.viite.dao
import scalikejdbc._

class SequenceResetterDAO extends BaseDAO {

  def resetSequenceToNumber(seqName: String, seqNumber: Long): Unit = {
    val seq = SQLSyntax.createUnsafely(seqName) // treat as SQL identifier

    // DROP sequence
    runUpdateToDb(sql"DROP SEQUENCE $seq")

    // CREATE sequence
    runUpdateToDb(SQL(
      s"""
         |CREATE SEQUENCE $seqName
         |START WITH $seqNumber
         |CACHE 20
         |INCREMENT BY 1
         |""".stripMargin
    ))
  }
}
