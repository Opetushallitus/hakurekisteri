package fi.vm.sade.hakurekisteri.storage.repository

import java.util.UUID
import java.util.concurrent.TimeUnit
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.{TestJournal, TestRepo, TestResource}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class JournaledRepositorySpec
    extends AnyFlatSpec
    with Matchers
    with RepositoryBehaviors[TestResource] {

  val repoConstructor = (items: Seq[TestResource]) => {
    val resources = items.map(TestResource.identify)
    TestRepo(TestJournal[TestResource](resources))
  }

  def itemConstructor: TestResource = {
    TestResource(java.util.UUID.randomUUID.toString)
  }

  def itemUpdater(
    original: TestResource with Identified[UUID]
  ): TestResource with Identified[UUID] = {
    TestResource(
      original.id,
      original.name,
      value = Some(original.value.getOrElse(original.name) + " updated")
    )
  }

  it should behave like basicRepoBehaviors(repoConstructor, itemConstructor, itemUpdater)

  abstract class Repo {
    val journal: Journal[TestResource, UUID]
    lazy val repo = TestRepo(journal)

  }

  trait EmptyJournal extends Repo {
    override val journal = new InMemJournal[TestResource, UUID]
  }

  trait JournalWithEntries extends Repo {
    val amount = 100
    val ids = Stream.continually(java.util.UUID.randomUUID).take(amount)
    val resources = Stream
      .continually(ids)
      .take(2)
      .flatten
      .zip(Stream.tabulate(amount * 2) { (i) =>
        if (i >= amount) s"updated${UUID.randomUUID}" else s"original${UUID.randomUUID}"
      })
      .map { case (id, round) => TestResource(id, round.toString, None) }
    val journal = TestJournal[TestResource](resources)
  }

  it should "add the modification to the journal" in new EmptyJournal {

    val idResource = saveItem(repo, TestResource("first item"))
    val delta: Delta[TestResource, UUID] = Updated(idResource)
    journal.journal(None).last should be(delta)

  }

  it should "not add a modification to the journal if the resource is not changed" in new EmptyJournal {

    val resu = TestResource("test")
    repo.save(resu)
    repo.save(resu)
    journal.journal(None).length should be(1)

  }

  "A repository with a journal with entries" should "contain all the resources in journal" in new JournalWithEntries {

    forAll(Table("id", ids: _*)) { (id) =>
      repo.get(id) should not be None
    }
  }

  it should "contain the latest version of a given resource" in new JournalWithEntries {

    forAll(Table("id", ids: _*)) { (id) =>
      repo.get(id).map(_.name.take(7)) should be(Some("updated"))

    }

  }

  it should "not contain resource which has deleted as latest delta" in new JournalWithEntries {

    val deleteJournal = TestJournal[TestResource](resources, ids)
    val deleteRepo = TestRepo(deleteJournal)
    forAll(Table("id", ids: _*)) { (id) =>
      deleteRepo.get(id).map(_.name) should be(None)

    }

  }

  it should "mark a delete delta in journal when deleted" in new JournalWithEntries {
    val resource = repo.get(ids.tail.head).get
    repo.delete(ids.head, source = "Test")
    val delta: Delta[TestResource, UUID] = Deleted(ids.head, source = "Test")
    journal.journal(None).last should be(delta)
  }

  it should "deduplicate when the same information is received multiple times" in new JournalWithEntries {
    val tr = TestResource("foo")
    val tr2 = TestResource("foo")

    val saved = saveItem(repo, tr)
    val saved2 = saveItem(repo, tr2)

    saved.id should be(saved2.id)
  }

  private def saveItem[T](repo: Repository[T, UUID], item: T): T with Identified[UUID] = {
    Await.result(repo.save(item), atMost = Duration(1, TimeUnit.SECONDS))
  }

}
