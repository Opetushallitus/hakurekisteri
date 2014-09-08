package fi.vm.sade.hakurekisteri.storage.repository

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables.Table
import java.util.UUID
import scala.annotation.tailrec
import fi.vm.sade.hakurekisteri.storage.Identified

trait RepositoryBehaviors[T] { this: FlatSpec with ShouldMatchers  =>

  trait RepoContext {
    val repoConstructor:Seq[T] => Repository[T,UUID]
    val initialItems:Seq[T]
    lazy val repo = repoConstructor(initialItems)
  }

  object RepoContext {
    def apply(constr: Seq[T] => Repository[T,UUID], ii: Seq[T]) = {
      new RepoContext {
        override val repoConstructor: (Seq[T]) => Repository[T,UUID] = constr
        override val initialItems: Seq[T] = ii
      }
    }

  }

  def repositorywithItems(repoContext: ((Repository[T,UUID], Seq[T], => T,  (T with Identified[UUID]) => T with Identified[UUID]) => Any) => Unit)  {


    it should "have same amount of items as saved" in repoContext {
      (repo, items, itemConstructor, itemUpdater) => repo.listAll().size should be (items.size)
    }

    it should "contain all items" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
      forAll(Table("id",items:_*)) {
        (item) => repo.listAll() should contain (item)
      }
    }

    it should "return item with id" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
      forAll(Table("id",repo.listAll():_*)) {
        (item) => repo.get(item.id) should be (Some(item))
      }

    }

    it should "return created item when created" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        forAll(Table("adds",Stream.continually(itemConstructor).take(10):_*)) {
          (item) => {
            val saved = repo.save(item)
            repo.get(saved.id) should be (Some(saved))
          }
        }
    }

    it should "contain created item when created" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        forAll(Table("adds",Stream.continually(itemConstructor).take(10):_*)) {
          (item) => repo.get(repo.save(item).id) should be (Some(item))
        }
    }


    it should "return updated item when updated" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        for (i <- repo.listAll().size to 10) repo.save(itemConstructor)
        forAll(Table("adds",repo.listAll().map(itemUpdater).take(10):_*)) {
          (item) => {
            val saved = repo.save(item)
            repo.get(saved.id) should be (Some(saved))
          }
        }
    }

    it should "contain updated item when updated" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        for (i <- repo.listAll().size to 10) repo.save(itemConstructor)
        forAll(Table("adds",repo.listAll().map(itemUpdater).take(10):_*)) {
          (item) => repo.get(repo.save(item).id) should be (Some(item))
        }
    }

    it should "retain id of the item when updated" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        for (i <- repo.listAll().size to 10) repo.save(itemConstructor)
        forAll(Table("adds",repo.listAll().map(itemUpdater).take(10):_*)) {
          (item) => repo.get(repo.save(item).id).map(_.id) should be (Some(item.id))
        }
    }

    it should "remove item when item is deleted" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        val saved = repo.save(itemConstructor)
        repo.delete(saved.id, source = "Test")
        repo.listAll should not(contain(saved))
    }


    it should "change cursor when item is created" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        val item = itemConstructor
        val start = repo.cursor(item)
        repo.save(item)
        repo.cursor(item) should not (be (start))
    }

    it should "change cursor when item is updated" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        val item = itemConstructor

        val saved = repo.listAll().headOption.getOrElse(repo.save(item))
        val start = repo.cursor(saved)
        repo.save(itemUpdater(saved))
        repo.cursor(saved) should not (be (start))
    }

    it should "change cursor when item is deleted" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        val item = itemConstructor

        val saved = repo.save(item)
        val start = repo.cursor(item)
        repo.delete(saved.id, source = "Test")
        repo.cursor(item) should not (be (start))
    }

    it should "not change cursor when delete is called on an item that doesn't exist" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        @tailrec def newId:UUID= {
          val id = UUID.randomUUID
          if (repo.listAll().map(_.id).contains(id)) newId else id
        }
        val start = repo.listAll().map(repo.cursor)
        repo.delete(newId, source = "Test")
        repo.listAll().map(repo.cursor) should be (start)
    }

    it should "change cursor when same item is updated twice" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        val item = repo.save(repo.listAll().headOption.getOrElse(itemConstructor))
        val start = repo.cursor(item)
        repo.save(itemUpdater(repo.listAll().head))
        repo.cursor(item) should not (be (start))
    }

    it should "change cursor when same item is updated in row" in repoContext {
      (repo, items, itemConstructor, itemUpdater) =>
        var item = repo.save(repo.listAll().headOption.getOrElse(itemConstructor))
        var start = repo.cursor(item)
        for( i <- 1 to 100) {
          start = repo.cursor(item)
          val newItem  = repo.save(itemUpdater(repo.listAll().head))
          repo.cursor(item) should not (be (start))
          item = newItem
        }

    }


  }

  def beforeAndAfterAdds(initialState:String, repoContext: ((Repository[T,UUID], Seq[T], => T,  (T with Identified[UUID]) => T with Identified[UUID]) => Any) => Unit) {
    def addContext(repoModifier: (Repository[T,UUID], Seq[T],  => T) => (Repository[T,UUID], Seq[T]))(test: (Repository[T,UUID], Seq[T],  => T,  (T with Identified[UUID]) => T with Identified[UUID]) => Any ) {
      repoContext((repo,items, itemConstructor, itemUpdater) =>
      {
        val (modRepo, modItems) = repoModifier(repo,items, itemConstructor)
        test(modRepo, modItems, itemConstructor, itemUpdater)

      }
      )
    }

    def repoAdder(amount:Int)(repo: Repository[T,UUID], items: Seq[T], itemConstructor: => T) = {
      val adds = Stream.continually(itemConstructor).take(amount)
      adds.foreach((item) => repo.save(item))
      (repo, items ++ adds)
    }

    def repoRemover(amount:Int)(repo: Repository[T,UUID], items: Seq[T], itemConstructor: => T) = {
      val deletes = repo.listAll().take(amount)
      deletes.foreach((item) => repo.delete(item.id, source = "Test"))
      (repo, items filter ( !deletes.contains(_)))
    }

    "%s with 100 adds".format(initialState) should behave like repositorywithItems(addContext(repoAdder(100)))
    "%s with no adds".format(initialState) should behave like repositorywithItems(addContext(repoAdder(0)))
    "%s with 10 removals".format(initialState) should behave like repositorywithItems(addContext(repoRemover(10)))
  }

  def basicRepoBehaviors(repoConstructor: Seq[T] => Repository[T, UUID], itemConstructor: => T, itemUpdater: (T with Identified[UUID]) => T with Identified[UUID] ) {
    def withRepo(items:Seq[T])(test: (Repository[T, UUID], Seq[T],  => T,  (T with Identified[UUID]) => T with Identified[UUID]) => Any  ) {
      test(repoConstructor(items), items, itemConstructor, itemUpdater)
    }
    it should behave like beforeAndAfterAdds("empty repo",withRepo(Seq()))
    it should behave like beforeAndAfterAdds("repo with 100 items",withRepo(Stream.continually(itemConstructor).take(100)))
  }



}
