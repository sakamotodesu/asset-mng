package controllers.data

import play.api.mvc._
import com.codahale.jerkson.Json._
import dao.Module._
import models._
import controllers.Application.AssetMngAction
import models.Undo
import models.Add
import models.HistoryEntry
import view.ViewHistoryEntry
import scala.Some
import models.Delete
import i18n.Messages
import org.apache.abdera.Abdera
import java.io.StringWriter
import xml.PrettyPrinter
import controllers.Application
import c10n.C10N

/**
 *
 * @author rodion
 */

object Activity extends Controller {
  def activity2view = (entry: HistoryEntry, m: Messages) =>
    ViewHistoryEntry(entry.id,
      entry.user,
      entry.dateStr,
      entry.action.localise(m),
      entry.obj.describe(m),
      canUndo(entry.action, entry))

  private def canUndo(action: HistoryAction, entry: HistoryEntry): Boolean = {
    action match {
      // 'undo' can be undone to one level
      case Undo(undoneAction) => undoneAction match {
        case Add() => canUndo(Delete(), entry)
        case Delete() => canUndo(Add(), entry)
        case Modify() => canUndo(Modify(), entry)
        case _ => false //Cannot undo an undo of undo for obvious reasons :D
      }
      // 'delete' can only be undo when the target item does not exist
      case Delete() => !findDB(entry.obj).all.exists(_.id == entry.obj.id)
      // 'add' and 'modify' actions can be undone as long as the item still exists
      case _ => findDB(entry.obj).all.exists(_.id == entry.obj.id)
    }
  }

  private def findDB(obj: HistoryObject) = obj match {
    case asset: Asset => assetsDB
    case assetTask: AssetTask => assetTasksDB
  }

  def list = AssetMngAction {
    implicit ctx => {
      Ok(generate(activityDB.all.reverse map (activity2view(_, ctx.m))))
    }
  }

  def atom = Action {
    implicit request =>
      val m = C10N.get(classOf[Messages], Application.getLocale(request))
      val history = activityDB.all.reverse
      val abdera = new Abdera
      val feed = abdera.newFeed
      val url = "http://" + Application.HOSTNAME
      feed.setId(url + "/activity/atom")
      feed.setTitle("Asset-mng Activity")
      history.headOption.map(entry => feed.setUpdated(entry.date))
      feed.addLink(url + "/activity")
      feed.addAuthor("rodion")
      for (entry <- history) {
        val feedEntry = feed.addEntry
        feedEntry.setId(url + "/activity/atom/" + entry.id)
        val title = m.activity.log(entry.obj.describe(m), entry.action.localise(m))
        feedEntry.setTitle(title)
        feedEntry.setSummaryAsHtml(title)
        feedEntry.setUpdated(entry.date)
        feedEntry.setPublished(entry.date)
        feedEntry.addLink(url + "/activity")
        feedEntry.addAuthor(entry.user)
      }
      val stringWriter = new StringWriter
      feed.writeTo(stringWriter)
      val xmlData = scala.xml.XML.loadString(stringWriter.toString)
      Ok(new PrettyPrinter(40, 2).format(xmlData))
        .withHeaders(("Cache-Control", "no-cache"), ("Content-Type", "application/atom+xml; charset=UTF-8"))
  }

  def undo(id: Long) = AssetMngAction {
    implicit ctx =>
      val newHistoryEntry = activityDB.all.find((entry) => entry.id == id && canUndo(entry.action, entry)) match {
        case Some(entry) => {
          entry.action match {
            case Add() => delete(entry, ctx.user)
            case Delete() => add(entry, ctx.user)
            case Modify() => update(entry, ctx.user)
            case Undo(action) => action match {
              case Add() => add(entry, ctx.user)
              case Delete() => delete(entry, ctx.user)
              case Modify() => update(entry, ctx.user)
              case _ => None
            }
            case _ => None
          }
        }
        case _ => None
      }
      newHistoryEntry match {
        case Some(entry) => Ok(generate(Map(
          "status" -> "OK",
          "activity" -> activity2view(entry, ctx.m))))
        case None => BadRequest(generate(Map("status" -> "ERROR")))
      }
  }

  private def delete(entry: HistoryEntry, user: String): Option[HistoryEntry] = {
    entry.obj match {
      case obj: Asset => Some(Assets.deleteAsset(obj.id, user, Undo(entry.action)))
      case obj: AssetTask => Some(AssetTasks.deleteTask(obj.id, user, Undo(entry.action)))
      case _ => None
    }
  }

  private def add(entry: HistoryEntry, user: String): Option[HistoryEntry] = {
    entry.obj match {
      case obj: Asset => Some(Assets.addAsset(obj, user, Undo(entry.action)))
      case obj: AssetTask => Some(AssetTasks.addTask(obj, user, Undo(entry.action)))
      case _ => None
    }
  }

  private def update(entry: HistoryEntry, user: String): Option[HistoryEntry] = {
    entry.obj match {
      case obj: Asset => Some(Assets.updateAsset(obj, user, Undo(entry.action)))
      case obj: AssetTask => Some(AssetTasks.updateTask(obj, user, Undo(entry.action)))
      case _ => None
    }
  }
}
