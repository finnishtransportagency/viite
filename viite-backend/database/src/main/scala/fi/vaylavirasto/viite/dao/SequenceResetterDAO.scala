package fi.vaylavirasto.viite.dao

import scalikejdbc.scalikejdbcSQLInterpolationImplicitDef

class SequenceResetterDAO extends BaseDAO {

  def resetSequenceToNumber(seqName: String, seqNumber: Long): Unit = {
    runUpdateToDb(sql"""DROP SEQUENCE #$seqName """)
    runUpdateToDb(
      sql"""
           CREATE SEQUENCE #$seqName
           START WITH #$seqNumber
           CACHE 20
           INCREMENT BY 1
           """
    )
  }
}

