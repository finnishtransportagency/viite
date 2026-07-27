package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.postgis.SessionProvider.session
import scalikejdbc._

object NotificationBannerDAO extends BaseDAO {

  def getMessage: Option[String] = {
    sql"""SELECT message FROM notification_banner WHERE id = 1"""
      .map(rs => rs.string("message"))
      .single()
      .apply()
      .filter(_.nonEmpty)
  }
}
