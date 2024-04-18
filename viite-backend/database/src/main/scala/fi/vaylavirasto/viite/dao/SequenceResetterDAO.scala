package fi.vaylavirasto.viite.dao

class SequenceResetterDAO extends BaseDAO {

  def resetSequenceToNumber(seqName: String, seqNumber: Long): Unit = {
    runUpdateToDb(s"""DROP SEQUENCE #$seqName """)
    runUpdateToDb(s"""CREATE SEQUENCE #$seqName START WITH #$seqNumber CACHE 20 INCREMENT BY 1""")
  }
}

