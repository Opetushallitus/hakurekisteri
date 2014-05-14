package fi.vm.sade.hakurekisteri

import org.scalatest.FlatSpec
import fi.vm.sade.hakurekisteri.storage.repository.{Delta, Updated, Deleted, Journal}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.prop.TableDrivenPropertyChecks._


/**
 * Created by verneri on 12.5.2014.
 */
class JournaledRepositorySpec extends FlatSpec with ShouldMatchers {


  trait EmptyJournal {
    val repo = TestRepo(TestJournal[TestResource]())
  }

  "A repository with an empty journal" should "be empty" in new EmptyJournal {

    repo.listAll == Seq()

  }

  it should "contain a resource when saved" in {
    val repo = TestRepo(TestJournal[TestResource]())
    val idResource = repo.save(TestResource("first item"))
    repo.get(idResource.id) === idResource

  }

  it should "add the modification to the journal" in {

    val journal = TestJournal[TestResource]()
    val idResource = TestRepo(journal).save(TestResource("first item"))
    val delta:Delta[TestResource] = Updated(idResource)
    journal.journal() should contain (delta)

  }

  val amount = 100
  val ids = Stream.continually(java.util.UUID.randomUUID).take(amount)
  val resources = Stream.continually(ids).take(2).flatten.zip(Stream.tabulate(amount * 2){(i) => if (i >= amount) "updated" else "original"}).map{case (id, round) => TestResource(id, round.toString)}
  val journal = TestJournal[TestResource](resources)
  val repo = TestRepo(journal)

  "A repository with a journal with entries" should "contain all the resources in journal" in {


    forAll(Table("id",ids:_*)) {
      (id) => repo.get(id) should not be (None)
    }
  }




  it should "contain the latest version of a given resource" in {

    forAll(Table("id",ids:_*)) {
      (id) => repo.get(id).map(_.name) should be (Some("updated"))

    }


  }

  it should "not contain resource which has deleted as latest delta" in {

    val deleteJournal = TestJournal[TestResource](resources, ids)
    val deleteRepo = TestRepo(deleteJournal)
    forAll(Table("id",ids:_*)) {
      (id) => deleteRepo.get(id).map(_.name) should be (None)

    }

  }

  it should "return the created resource" in {

    val resource = TestResource("jiihaa")
    val saved = repo.save(resource)
    saved === resource

  }

  it should "return the updated resource when updated" in {
    val resource = TestResource(ids.head, "juhuuu")
    val saved = repo.save(resource)
    saved === resource

  }

  it should "keep the id of an updated resource" in {
    val resource = TestResource(ids.head, "juhuuu")
    val saved = repo.save(resource)
    saved.id === resource.id
  }


  it should "delete a resource when requested" in {
    val resource = repo.get(ids.head).get
    repo.delete(ids.head)
    repo.listAll should not(contain (resource))
  }

  it should "mark a delete delta in journal when deleted" in {
    val resource = repo.get(ids.tail.head).get
    repo.delete(ids.head)
    val delta:Delta[TestResource] = Deleted(ids.head)
    journal.journal should contain (delta)
  }




}
