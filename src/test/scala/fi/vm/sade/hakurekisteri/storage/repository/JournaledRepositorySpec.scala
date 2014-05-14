package fi.vm.sade.hakurekisteri.storage.repository

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import scala.Some
import fi.vm.sade.hakurekisteri.{TestJournal, TestRepo, TestResource}
import fi.vm.sade.hakurekisteri.storage.Identified


class JournaledRepositorySpec extends FlatSpec with ShouldMatchers with RepositoryBehaviors[TestResource] {

  val repoConstructor = (items:Seq[TestResource]) => {
    val resources = items.map(TestResource.identify(_))
    TestRepo(TestJournal[TestResource](resources))
  }

  def itemConstructor:TestResource = {
    TestResource(java.util.UUID.randomUUID.toString)
  }

  it should behave like basicRepoBehaviors(repoConstructor, itemConstructor)

  abstract class Repo {
    val journal:Journal[TestResource]
    lazy val repo = TestRepo(journal)

  }

  trait EmptyJournal extends Repo {
    override val journal = new InMemJournal[TestResource]
  }

  trait JournalWithEntries extends Repo {
    val amount = 100
    val ids = Stream.continually(java.util.UUID.randomUUID).take(amount)
    val resources = Stream.continually(ids).take(2).flatten.zip(Stream.tabulate(amount * 2){(i) => if (i >= amount) "updated" else "original"}).map{case (id, round) => TestResource(id, round.toString)}
    val journal = TestJournal[TestResource](resources)
  }

  "A repository with an empty journal" should "be empty" in new EmptyJournal {
    repo.listAll == Seq()

  }

  it should "contain a resource when saved" in new EmptyJournal {
    val resource = TestResource("first item")
    val idResource = repo.save(resource)
    repo.get(idResource.id) === resource

  }



  it should "add the modification to the journal" in {

    val journal = TestJournal[TestResource]()
    val idResource = TestRepo(journal).save(TestResource("first item"))
    val delta:Delta[TestResource] = Updated(idResource)
    journal.journal() should contain (delta)

  }


  "A repository with a journal with entries" should "contain all the resources in journal" in new JournalWithEntries {
    forAll(Table("id",ids:_*)) {
      (id) => repo.get(id) should not be (None)
    }
  }




  it should "contain the latest version of a given resource" in new JournalWithEntries {

    forAll(Table("id",ids:_*)) {
      (id) => repo.get(id).map(_.name) should be (Some("updated"))

    }


  }

  it should "not contain resource which has deleted as latest delta" in new JournalWithEntries {

    val deleteJournal = TestJournal[TestResource](resources, ids)
    val deleteRepo = TestRepo(deleteJournal)
    forAll(Table("id",ids:_*)) {
      (id) => deleteRepo.get(id).map(_.name) should be (None)

    }

  }

  it should "return the created resource" in new JournalWithEntries {

    val resource = TestResource("jiihaa")
    val saved = repo.save(resource)
    saved === resource

  }

  it should "return the updated resource when updated" in new JournalWithEntries {
    val resource = TestResource(ids.head, "juhuuu")
    val saved = repo.save(resource)
    saved === resource

  }

  it should "keep the id of an updated resource" in new JournalWithEntries {
    val resource = TestResource(ids.head, "juhuuu")
    val saved = repo.save(resource)
    saved.id === resource.id
  }


  it should "delete a resource when requested" in new JournalWithEntries {

    repo.delete(ids.head)
    repo.get(ids.head) should be (None)
  }

  it should "mark a delete delta in journal when deleted" in new JournalWithEntries {
    val resource = repo.get(ids.tail.head).get
    repo.delete(ids.head)
    val delta:Delta[TestResource] = Deleted(ids.head)
    journal.journal should contain (delta)
  }




}
