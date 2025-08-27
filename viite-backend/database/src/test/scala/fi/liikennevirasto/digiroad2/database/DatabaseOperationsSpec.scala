//noinspection SqlResolve <-- This comment is necessary to avoid false positive warnings in the IDE
package fi.liikennevirasto.digiroad2.database

import fi.vaylavirasto.viite.dao.BaseDAO
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC._
import fi.vaylavirasto.viite.postgis.SessionProvider
import scalikejdbc._

class DatabaseOperationsSpec extends AnyFunSuite with Matchers with BaseDAO {

  // Helper method to create a simple test table for our tests
  private def createTestTable(): Unit = {
    runUpdateToDb(sql"""
      CREATE TEMPORARY TABLE IF NOT EXISTS session_test_table (
        id SERIAL PRIMARY KEY,
        value VARCHAR(50)
      )
    """)
  }

  private def insertTestData(value: String): Long = {
    val id = runSelectSingleFirstWithType[Long](sql"SELECT nextval('session_test_table_id_seq')")
    runUpdateToDb(sql"INSERT INTO session_test_table (id, value) VALUES ($id, $value)")
    id
  }

  private def selectTestData(id: Long): Option[String] = {
    runSelectFirst(sql"SELECT value FROM session_test_table WHERE id = $id"
      .map(_.string("value")))
  }


  test("Test SessionProvider.session throws IllegalStateException when no session is set") {
    // This test runs outside any session context
    intercept[IllegalStateException] {
      SessionProvider.session
    }
  }

  test("Test runWithTransaction provides valid session") {
    runWithTransaction {
      // Session should be available implicitly
      SessionProvider.session should not be null
      SessionProvider.session.tx should be(defined)
    }
  }

  test("Test runWithReadOnlySession provides valid read-only session") {
    runWithReadOnlySession {
      SessionProvider.session should not be null
      SessionProvider.session.isReadOnly should be(true)
    }
  }

  test("Test nested transaction throws IllegalStateException") {
    runWithTransaction {
      intercept[IllegalStateException] {
        runWithTransaction {
          // This should fail - nested transaction
          "should not reach here"
        }
      }
    }
  }

  test("Test transaction inside read-only session throws IllegalStateException") {
    runWithReadOnlySession {
      intercept[IllegalStateException] {
        runWithTransaction {
          // This should fail - transaction inside read-only
          "should not reach here"
        }
      }
    }
  }

  test("Test read-only session inside transaction can see uncommitted changes") {
    runWithRollback {
      createTestTable()

      // Insert data in the transaction
      val testValue = "transaction_test"
      val id = insertTestData(testValue)

      // Read-only session inside transaction should see the uncommitted data
      runWithReadOnlySession {
        val result = selectTestData(id)
        result should be(Some(testValue))

        // Verify we're in read-only mode by attempting a write operation
        intercept[java.sql.SQLException] {
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('should_fail')")
        }
      }

      // Verify the data is still there after read-only session ends
      val resultAfterReadOnly = selectTestData(id)
      resultAfterReadOnly should be(Some(testValue))
    }
  }

  test("Test multiple nested read-only sessions inside transaction") {
    runWithRollback {
      createTestTable()

      val testValue1 = "nested_test_1"
      val testValue2 = "nested_test_2"

      val id1 = insertTestData(testValue1)

      runWithReadOnlySession {
        // First read-only session should see the data
        selectTestData(id1) should be(Some(testValue1))

        runWithReadOnlySession {
          // Nested read-only session should also see the data
          selectTestData(id1) should be(Some(testValue1))
        }
      }

      // After both read-only sessions, we should still be in the transaction
      val id2 = insertTestData(testValue2) // This should work
      selectTestData(id2) should be(Some(testValue2))
    }
  }

  test("Test session restoration and write blocking") {
    runWithTransaction {
      createTestTable()
      val originalSession = SessionProvider.session

      runWithReadOnlySession {
        // Verify write blocking is active
        an[java.sql.SQLException] should be thrownBy {
          SessionProvider.checkWriteAllowed()
        }

        // Try a write operation through BaseDAO
        an[java.sql.SQLException] should be thrownBy {
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('should_fail')")
        }
      }

      // After read-only session, writes should work again
      val restoredSession = SessionProvider.session
      restoredSession should be(originalSession)
      restoredSession.tx should be(defined)

      // This should work (no exception thrown)
      noException should be thrownBy {
        insertTestData("should_succeed")
      }
    }
  }

  test("Test read-only session inside transaction uses existing session for reads") {
    runWithRollback {
      createTestTable()

      val transactionSession = SessionProvider.session
      val testValue = "session_reuse_test"
      val id = insertTestData(testValue)

      runWithReadOnlySession {
        // The read-only session should be able to see uncommitted changes
        // because it uses the existing transaction session for reads
        val result = selectTestData(id)
        result should be(Some(testValue))

        // But writes should still fail
        intercept[java.sql.SQLException] {
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('write_should_fail')")
        }
      }

      // Verify we're back to the original transaction session
      SessionProvider.session should be(transactionSession)
    }
  }

  test("Test read-only session inside transaction blocks all write operations") {
    runWithRollback {
      createTestTable()

      runWithReadOnlySession {
        // Test runUpdateToDb
        an[java.sql.SQLException] should be thrownBy {
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('blocked1')")
        }

        // Test runBatchUpdateToDb
        an[java.sql.SQLException] should be thrownBy {
          runBatchUpdateToDb(
            sql"INSERT INTO session_test_table (value) VALUES (?)",
            Seq(Seq("blocked2"), Seq("blocked3"))
          )
        }

        // Test the helper insertTestData method
        an[java.sql.SQLException] should be thrownBy {
          insertTestData("blocked4")
        }

        // But reads should still work
        noException should be thrownBy {
          runSelectQuery(sql"SELECT COUNT(*) FROM session_test_table".map(_.int(1)))
        }
      }
    }
  }

  test("Test nested read-only sessions preserve write blocking") {
    runWithRollback {
      createTestTable()

      runWithReadOnlySession {
        // First level read-only
        an[java.sql.SQLException] should be thrownBy {
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('outer_blocked')")
        }

        runWithReadOnlySession {
          // Nested read-only should also block writes
          an[java.sql.SQLException] should be thrownBy {
            runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('inner_blocked')")
          }

          // But reads work
          runSelectQuery(sql"SELECT COUNT(*) FROM session_test_table".map(_.int(1)))
        }

        // After nested, still blocked
        an[java.sql.SQLException] should be thrownBy {
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('still_blocked')")
        }
      }
    }
  }

  test("Test standalone read-only session allows no writes") {
    // Test read-only session NOT inside a transaction
    runWithReadOnlySession {
      // Don't call createTestTable() here - it will throw immediately

      an[java.sql.SQLException] should be thrownBy {
        runUpdateToDb(sql"CREATE TEMPORARY TABLE standalone_test (id INT)")
      }
    }
  }

  test("Test write blocking is removed after exiting nested read-only sessions") {
    runWithRollback {
      createTestTable()

      // Should work - we're in transaction
      insertTestData("tx_data")

      runWithReadOnlySession {
        // Should be blocked
        an[java.sql.SQLException] should be thrownBy {
          insertTestData("readonly1_blocked")
        }

        runWithReadOnlySession {
          // Still blocked
          an[java.sql.SQLException] should be thrownBy {
            insertTestData("readonly2_blocked")
          }
        }

        // Still blocked after inner read-only ends
        an[java.sql.SQLException] should be thrownBy {
          insertTestData("readonly1_still_blocked")
        }
      }

      // Should work again - back in transaction context
      noException should be thrownBy {
        insertTestData("tx_data_after_readonly")
      }
    }
  }

  test("Test error handling in read-only session doesn't affect transaction") {
    runWithRollback {
      createTestTable()
      val id1 = insertTestData("before_readonly")

      // This should not affect the transaction even if exception occurs
      try {
        runWithReadOnlySession {
          // Verify we can see the data
          selectTestData(id1) should be(Some("before_readonly"))

          // This will throw SQLException
          runUpdateToDb(sql"INSERT INTO session_test_table (value) VALUES ('should_fail')")
        }
      } catch {
        case _: java.sql.SQLException => // Expected
      }

      // Transaction should still be valid and we can continue
      val id2 = insertTestData("after_readonly_error")
      selectTestData(id2) should be(Some("after_readonly_error"))
    }
  }

  test("Test mixed read and write operations in complex nesting") {
    runWithRollback {
      createTestTable()

      // Insert some initial data
      val id1 = insertTestData("initial")

      runWithReadOnlySession {
        // Can read existing data
        selectTestData(id1) should be(Some("initial"))

        // Cannot write
        an[java.sql.SQLException] should be thrownBy {
          insertTestData("blocked_in_readonly")
        }
      }

      // Back in transaction - can write again
      val id2 = insertTestData("after_readonly")

      runWithReadOnlySession {
        // Can see both pieces of data (including uncommitted)
        selectTestData(id1) should be(Some("initial"))
        selectTestData(id2) should be(Some("after_readonly"))

        // But still cannot write
        an[java.sql.SQLException] should be thrownBy {
          insertTestData("still_blocked")
        }
      }

      // Final write should work
      val id3 = insertTestData("final")
      selectTestData(id3) should be(Some("final"))
    }
  }

  test("Test checkWriteAllowed can be called directly") {
    runWithTransaction {
      // In regular transaction, should not throw
      noException should be thrownBy {
        SessionProvider.checkWriteAllowed()
      }

      runWithReadOnlySession {
        // In read-only session inside transaction, should throw
        an[java.sql.SQLException] should be thrownBy {
          SessionProvider.checkWriteAllowed()
        }
      }

      // Back in transaction, should not throw again
      noException should be thrownBy {
        SessionProvider.checkWriteAllowed()
      }
    }
  }

}
