package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.viite.NewIdValue
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class JunctionDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val dao = new JunctionDAO
  val nodeDAO = new NodeDAO

  val testJunction1 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-01"), None,
    DateTime.parse("2019-01-01"), None, "Test", None)

  val testJunction2 = Junction(NewIdValue, None, None, DateTime.parse("2019-01-02"), None,
    DateTime.parse("2019-01-02"), None, "Test", None)

  val testNode1 = Node(NewIdValue, NewIdValue, Point(100, 100), Some("Test node 1"), NodeType.NormalIntersection,
    DateTime.parse("2019-01-01"), None, DateTime.parse("2019-01-01"), None, "Test", None, registrationDate = new DateTime())

  test("Test create When nothing to create Then return empty Seq") {
    runWithRollback {
      val ids = dao.create(Seq())
      ids.isEmpty should be(true)
    }
  }

  test("Test create When one created Then return Seq with one id") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1))
      ids.size should be(1)
    }
  }

  test("Test create When two created Then return Seq with two ids") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1, testJunction2))
      ids.size should be(2)
    }
  }

  test("Test expireById When two templates created and one expired Then expire one and keep the other") {
    runWithRollback {
      val ids = dao.create(Seq(testJunction1, testJunction2))
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
    }
  }

  test("Test expireById When two created and one expired Then expire one and keep the other") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val ids = dao.create(Seq(testJunction1.copy(nodeNumber = Some(nodeNumber)), testJunction2.copy(nodeNumber = Some(nodeNumber))))
      dao.expireById(Seq(ids.head))
      val fetched = dao.fetchByIds(ids)
      fetched.size should be(1)
      fetched.head.id should be(ids.last)
    }
  }

  test("Test fetchJunctionByNodeIds When non-existing node Then return none") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      dao.create(Seq(testJunction1.copy(nodeNumber = Some(nodeNumber)), testJunction2.copy(nodeNumber = Some(nodeNumber))))
      val fetched = dao.fetchJunctionsByNodeNumbers(Seq(nodeNumber + 1)) // Non-existing node id
      fetched.size should be(0)
    }
  }

  test("Test fetchJunctionByNodeIds When fetched Then return junctions") {
    runWithRollback {
      val nodeNumber = nodeDAO.create(Seq(testNode1)).head
      val fetched1 = dao.fetchJunctionsByNodeNumbers(Seq(nodeNumber))
      fetched1.size should be(0)
      dao.create(Seq(testJunction1.copy(nodeNumber = Some(nodeNumber)), testJunction2.copy(nodeNumber = Some(nodeNumber))))
      val fetched2 = dao.fetchJunctionsByNodeNumbers(Seq(nodeNumber))
      fetched2.size should be(2)
    }
  }

}
