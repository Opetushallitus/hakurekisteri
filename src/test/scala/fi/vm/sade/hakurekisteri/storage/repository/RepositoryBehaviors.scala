package fi.vm.sade.hakurekisteri.storage.repository

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables.Table
import java.util.UUID
import scala.annotation.tailrec

trait RepositoryBehaviors[T] { this: FlatSpec with ShouldMatchers  =>

  trait RepoContext {
    val repoConstructor:Seq[T] => Repository[T]
    val initialItems:Seq[T]
    lazy val repo = repoConstructor(initialItems)
  }

  object RepoContext {
    def apply(constr: Seq[T] => Repository[T], ii: Seq[T]) = {
      new RepoContext {
        override val repoConstructor: (Seq[T]) => Repository[T] = constr
        override val initialItems: Seq[T] = ii
      }
    }

  }

  def repositorywithItems(repoContext: ((Repository[T], Seq[T], => T) => Any) => Unit)  {


    it should "have same amount of items as saved" in repoContext {
      (repo, items, itemConstructor) => repo.listAll().size should be (items.size)
    }

    it should "contain all items" in repoContext {
      (repo, items, itemConstructor) =>
      forAll(Table("id",items:_*)) {
        (item) => repo.listAll() should contain (item)
      }
    }

    it should "return item with id" in repoContext {
      (repo, items, itemConstructor) =>
      forAll(Table("id",repo.listAll():_*)) {
        (item) => repo.get(item.id) should be (Some(item))
      }

    }

    it should "return created item when created" in repoContext {
      (repo, items, itemConstructor) =>
        forAll(Table("adds",Stream.continually(itemConstructor).take(10):_*)) {
          (item) => {
            val saved = repo.save(item)
            repo.get(saved.id) should be (Some(saved))
          }
        }
    }

    it should "contain created item when created" in repoContext {
      (repo, items, itemConstructor) =>
        forAll(Table("adds",Stream.continually(itemConstructor).take(10):_*)) {
          (item) => repo.get(repo.save(item).id) should be (Some(item))
        }
    }

    it should "remove item when item is deleted" in repoContext {
      (repo, items, itemConstructor) =>
        val saved = repo.save(itemConstructor)
        repo.delete(saved.id)
        repo.listAll should not(contain(saved))
    }


    it should "change cursor when item is created" in repoContext {
      (repo, items, itemConstructor) =>
        val start = repo.cursor
        repo.save(itemConstructor)
        repo.cursor should not (be (start))
    }

    it should "change cursor when item is updated" in repoContext {
      (repo, items, itemConstructor) =>
        val start = repo.cursor
        repo.save(itemConstructor)
        repo.cursor should not (be (start))
    }

    it should "change cursor when item is deleted" in repoContext {
      (repo, items, itemConstructor) =>
        val saved = repo.save(itemConstructor)
        val start = repo.cursor
        repo.delete(saved.id)
        repo.cursor should not (be (start))
    }

    it should "not change cursor when delete is called on an item that doesn't exist" in repoContext {
      (repo, items, itemConstructor) =>
        @tailrec def newId:UUID= {
          val id = UUID.randomUUID
          if (repo.listAll().map(_.id).contains(id)) newId else id
        }
        val start = repo.cursor
        repo.delete(newId)
        repo.cursor should be (start)
    }

    it should "change cursor when same item is updated twice" in repoContext {
      (repo, items, itemConstructor) =>
        repo.save(repo.listAll().headOption.getOrElse(itemConstructor))
        val start = repo.cursor
        repo.save(repo.listAll().head)
        repo.cursor should not (be (start))
    }

    it should "change cursor when same item is updated in row" in repoContext {
      (repo, items, itemConstructor) =>
        repo.save(repo.listAll().headOption.getOrElse(itemConstructor))
        var start = repo.cursor
        val head = repo.listAll().head
        for( i <- (1 to 100)) {
          start = repo.cursor
          repo.save(head)
          repo.cursor should not (be (start))

        }

    }


  }

  def beforeAndAfterAdds(initialState:String, repoContext: ((Repository[T], Seq[T], => T) => Any) => Unit) {
    def addContext(repoModifier: (Repository[T], Seq[T],  => T) => (Repository[T], Seq[T]))(test: (Repository[T], Seq[T],  => T) => Any ) {
      repoContext((repo,items, itemConstructor) =>
      {
        val (modRepo, modItems) = repoModifier(repo,items, itemConstructor)
        test(modRepo, modItems, itemConstructor)

      }
      )
    }

    def repoAdder(amount:Int)(repo: Repository[T], items: Seq[T], itemConstructor: => T) = {
      val adds = Stream.continually(itemConstructor).take(amount)
      adds.foreach((item) => repo.save(item))
      (repo, items ++ adds)
    }

    def repoRemover(amount:Int)(repo: Repository[T], items: Seq[T], itemConstructor: => T) = {
      val deletes = repo.listAll().take(amount)
      deletes.foreach((item) => repo.delete(item.id))
      (repo, items filter ( !deletes.contains(_)))
    }

    "%s with 100 adds".format(initialState) should behave like repositorywithItems(addContext(repoAdder(100)))
    "%s with no adds".format(initialState) should behave like repositorywithItems(addContext(repoAdder(0)))
    "%s with 10 removals".format(initialState) should behave like repositorywithItems(addContext(repoRemover(10)))
  }

  def basicRepoBehaviors(repoConstructor: Seq[T] => Repository[T], itemConstructor: => T) {
    def withRepo(items:Seq[T])(test: (Repository[T], Seq[T],  => T) => Any ) {
      test(repoConstructor(items), items, itemConstructor)
    }
    it should behave like beforeAndAfterAdds("empty repo",withRepo(Seq()))
    it should behave like beforeAndAfterAdds("repo with 100 items",withRepo(Stream.continually(itemConstructor).take(100)))
  }



}
