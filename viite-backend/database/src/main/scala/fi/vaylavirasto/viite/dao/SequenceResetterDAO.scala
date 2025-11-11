package fi.vaylavirasto.viite.dao

import scalikejdbc._

class SequenceResetterDAO extends BaseDAO {

  def resetSequenceToNumber(seqName: String, seqNumber: Long): Unit = {
    runUpdateToDb(sql"""DROP SEQUENCE IF EXISTS #$seqName""")
    runUpdateToDb(
      sql"""
        CREATE SEQUENCE #$seqName
        START WITH $seqNumber
        INCREMENT BY 1
        CACHE 20
      """
    )
  }
}
