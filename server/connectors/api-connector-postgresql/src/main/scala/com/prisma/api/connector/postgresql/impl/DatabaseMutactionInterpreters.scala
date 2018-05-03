package com.prisma.api.connector.postgresql.impl

import java.sql.SQLIntegrityConstraintViolationException

import com.prisma.api.connector._
import com.prisma.api.connector.postgresql.DatabaseMutactionInterpreter
import com.prisma.api.connector.postgresql.database.PostGresApiDatabaseMutationBuilder
import com.prisma.api.connector.postgresql.database.PostGresApiDatabaseMutationBuilder.{
  cascadingDeleteChildActions,
  oldParentFailureTriggerByField,
  oldParentFailureTriggerByFieldAndFilter
}
import com.prisma.api.connector.postgresql.impl.GetFieldFromSQLUniqueException.getFieldOption
import com.prisma.api.schema.APIErrors
import com.prisma.api.schema.APIErrors.RequiredRelationWouldBeViolated
import com.prisma.shared.models.{Field, Relation}
import org.postgresql.util.PSQLException
import slick.dbio.DBIOAction
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

case class AddDataItemToManyRelationByPathInterpreter(mutaction: AddDataItemToManyRelationByPath)(implicit val ec: ExecutionContext)
    extends DatabaseMutactionInterpreter {

  override val action = PostGresApiDatabaseMutationBuilder.createRelationRowByPath(mutaction.project.id, mutaction.path)
}

case class CascadingDeleteRelationMutactionsInterpreter(mutaction: CascadingDeleteRelationMutactions)(implicit val ec: ExecutionContext)
    extends DatabaseMutactionInterpreter {
  val path    = mutaction.path
  val project = mutaction.project

  val fieldsWhereThisModelIsRequired = project.schema.fieldsWhereThisModelIsRequired(path.lastModel)

  val otherFieldsWhereThisModelIsRequired = path.lastEdge match {
    case Some(edge) => fieldsWhereThisModelIsRequired.filter(f => f != edge.parentField)
    case None       => fieldsWhereThisModelIsRequired
  }

  override val action = {
    val requiredCheck = otherFieldsWhereThisModelIsRequired.map(field => oldParentFailureTriggerByField(project, path, field, causeString(field)))
    val deleteAction  = List(cascadingDeleteChildActions(project.id, path))
    val allActions    = requiredCheck ++ deleteAction
    DBIOAction.seq(allActions: _*).map(mapToUnitResult)
  }

  override def errorMapper = {
    case e: PSQLException if otherFailingRequiredRelationOnChild(e.getMessage).isDefined =>
      throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getMessage).get)
  }

  private def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] =
    otherFieldsWhereThisModelIsRequired.collectFirst { case f if cause.contains(causeString(f)) => f.relation.get }

  private def causeString(field: Field) = path.lastEdge match {
    case Some(edge: NodeEdge) =>
      s"-OLDPARENTPATHFAILURETRIGGERBYFIELD@${field.relation.get.relationTableName}@${field.oppositeRelationSide.get}@${edge.childWhere.fieldValueAsString}-"
    case _ => s"-OLDPARENTPATHFAILURETRIGGERBYFIELD@${field.relation.get.relationTableName}@${field.oppositeRelationSide.get}-"
  }
}

case class CreateDataItemInterpreter(mutaction: CreateDataItem, includeRelayRow: Boolean = true)(implicit val ec: ExecutionContext)
    extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val path    = mutaction.path

  override val action = {
    val createNonList = PostGresApiDatabaseMutationBuilder.createDataItem(project.id, path, mutaction.nonListArgs)
    val listAction    = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, path, mutaction.listArgs)

    if (includeRelayRow) {
      val createRelayRow = PostGresApiDatabaseMutationBuilder.createRelayRow(project.id, path)
      DBIO.sequence(Vector(createNonList, createRelayRow, listAction)).map(_.head)
    } else {
      DBIO.sequence(Vector(createNonList, listAction)).map(_.head)
    }
  }

  override val errorMapper = {
    case e: PSQLException if e.getSQLState == "23505" && GetFieldFromSQLUniqueException.getFieldOption(mutaction.nonListArgs.keys, e).isDefined =>
      APIErrors.UniqueConstraintViolation(path.lastModel.name, GetFieldFromSQLUniqueException.getFieldOption(mutaction.nonListArgs.keys, e).get)
    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeDoesNotExist("")
  }
}

case class DeleteDataItemInterpreter(mutaction: DeleteDataItem)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  override val action = DBIO
    .seq(
      PostGresApiDatabaseMutationBuilder.deleteRelayRow(mutaction.project.id, mutaction.path),
      PostGresApiDatabaseMutationBuilder.deleteDataItem(mutaction.project.id, mutaction.path)
    )
    .map(mapToUnitResult)
}

case class DeleteDataItemNestedInterpreter(mutaction: DeleteDataItemNested)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  override val action = DBIO
    .seq(
      PostGresApiDatabaseMutationBuilder.deleteRelayRow(mutaction.project.id, mutaction.path),
      PostGresApiDatabaseMutationBuilder.deleteDataItem(mutaction.project.id, mutaction.path)
    )
    .map(mapToUnitResult)
}

case class DeleteDataItemsInterpreter(mutaction: DeleteDataItems)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  override val action = DBIOAction
    .seq(
      PostGresApiDatabaseMutationBuilder.deleteRelayIds(mutaction.project, mutaction.model, mutaction.whereFilter),
      PostGresApiDatabaseMutationBuilder.deleteDataItems(mutaction.project, mutaction.model, mutaction.whereFilter)
    )
    .map(mapToUnitResult)
}

case class DeleteManyRelationChecksInterpreter(mutaction: DeleteManyRelationChecks)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val model   = mutaction.model
  val filter  = mutaction.whereFilter

  val fieldsWhereThisModelIsRequired = project.schema.fieldsWhereThisModelIsRequired(model)

  override val action = {
    val requiredChecks = fieldsWhereThisModelIsRequired.map(field => oldParentFailureTriggerByFieldAndFilter(project, model, filter, field, causeString(field)))
    DBIOAction.seq(requiredChecks: _*).map(mapToUnitResult)
  }

  override def errorMapper = {
    case e: PSQLException if otherFailingRequiredRelationOnChild(e.getMessage).isDefined =>
      throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getMessage).get)
  }

  private def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] = fieldsWhereThisModelIsRequired.collectFirst {
    case f if cause.contains(causeString(f)) => f.relation.get
  }

  private def causeString(field: Field) =
    s"-OLDPARENTPATHFAILURETRIGGERBYFIELDANDFILTER@${field.relation.get.relationTableName}@${field.oppositeRelationSide.get}-"

}

case class DeleteRelationCheckInterpreter(mutaction: DeleteRelationCheck)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val path    = mutaction.path

  val fieldsWhereThisModelIsRequired = project.schema.fieldsWhereThisModelIsRequired(path.lastModel)

  override val action = {
    val requiredCheck = fieldsWhereThisModelIsRequired.map(field => oldParentFailureTriggerByField(project, path, field, causeString(field)))
    DBIOAction.seq(requiredCheck: _*).map(mapToUnitResult)
  }

  override val errorMapper = {
    case e: PSQLException if otherFailingRequiredRelationOnChild(e.getMessage).isDefined =>
      throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getMessage).get)
  }

  private def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] =
    fieldsWhereThisModelIsRequired.collectFirst { case f if cause.contains(causeString(f)) => f.relation.get }

  private def causeString(field: Field) = path.lastEdge match {
    case Some(edge: NodeEdge) =>
      s"-OLDPARENTPATHFAILURETRIGGERBYFIELD@${field.relation.get.relationTableName}@${field.oppositeRelationSide.get}@${edge.childWhere.fieldValueAsString}-"
    case _ => s"-OLDPARENTPATHFAILURETRIGGERBYFIELD@${field.relation.get.relationTableName}@${field.oppositeRelationSide.get}-"
  }
}

case class ResetDataInterpreter(mutaction: ResetDataMutaction)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val truncateTables  = DBIOAction.seq(mutaction.tableNames.map(PostGresApiDatabaseMutationBuilder.truncateTable(mutaction.project.id, _)): _*)
  override val action = DBIOAction.seq(truncateTables).map(mapToUnitResult)
}

case class UpdateDataItemInterpreter(mutaction: UpdateWrapper)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val (project, path, nonListArgs, listArgs) = mutaction match {
    case x: UpdateDataItem       => (x.project, x.path, x.nonListArgs, x.listArgs)
    case x: NestedUpdateDataItem => (x.project, x.path, x.nonListArgs, x.listArgs)
  }

  val nonListAction = PostGresApiDatabaseMutationBuilder.updateDataItemByPath(project.id, path, nonListArgs)
  val listAction    = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, path, listArgs)

  override val action = DBIO.seq(listAction, nonListAction).map(mapToUnitResult)

  override val errorMapper = {
    // https://dev.mysql.com/doc/refman/5.5/en/error-messages-server.html#error_er_dup_entry
    case e: PSQLException if e.getSQLState == "23505" && GetFieldFromSQLUniqueException.getFieldOption(nonListArgs.keys, e).isDefined =>
      APIErrors.UniqueConstraintViolation(path.lastModel.name, GetFieldFromSQLUniqueException.getFieldOption(nonListArgs.keys, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeNotFoundForWhereError(path.root)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull()
  }
}

case class UpdateDataItemsInterpreter(mutaction: UpdateDataItems)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val nonListActions = PostGresApiDatabaseMutationBuilder.updateDataItems(mutaction.project.id, mutaction.model, mutaction.updateArgs, mutaction.whereFilter)
  val listActions    = PostGresApiDatabaseMutationBuilder.setManyScalarLists(mutaction.project.id, mutaction.model, mutaction.listArgs, mutaction.whereFilter)

  //update Lists before updating the nodes
  override val action = DBIOAction.seq(listActions, nonListActions).map(mapToUnitResult)
}

case class UpsertDataItemInterpreter(mutaction: UpsertDataItem)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val model      = mutaction.updatePath.lastModel
  val project    = mutaction.project
  val createArgs = mutaction.nonListCreateArgs
  val updateArgs = mutaction.nonListUpdateArgs

  override val action = {
    val createAction = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, mutaction.createPath, mutaction.listCreateArgs)
    val updateAction = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, mutaction.updatePath, mutaction.listUpdateArgs)
    PostGresApiDatabaseMutationBuilder.upsert(project.id, mutaction.createPath, mutaction.updatePath, createArgs, updateArgs, createAction, updateAction)
  }.map(mapToUnitResult)

  override val errorMapper = {
    case e: PSQLException if e.getSQLState == "23505" && getFieldOption(createArgs.keys ++ updateArgs.keys, e).isDefined =>
      APIErrors.UniqueConstraintViolation(model.name, getFieldOption(createArgs.keys ++ updateArgs.keys, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeDoesNotExist("") //todo

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull(e.getCause.getMessage)
  }
}

case class UpsertDataItemIfInRelationWithInterpreter(mutaction: UpsertDataItemIfInRelationWith)(implicit val ec: ExecutionContext)
    extends DatabaseMutactionInterpreter {
  val project = mutaction.project

  val scalarListsCreate = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, mutaction.createPath, mutaction.createListArgs)
  val scalarListsUpdate = PostGresApiDatabaseMutationBuilder.setScalarList(project.id, mutaction.updatePath, mutaction.updateListArgs)
  val relationChecker   = NestedCreateRelationInterpreter(NestedCreateRelation(project, mutaction.createPath, false))
  val createCheck       = DBIOAction.seq(relationChecker.allActions: _*)

  override val action = PostGresApiDatabaseMutationBuilder
    .upsertIfInRelationWith(
      project = project,
      createPath = mutaction.createPath,
      updatePath = mutaction.updatePath,
      createArgs = mutaction.createNonListArgs,
      updateArgs = mutaction.updateNonListArgs,
      scalarListCreate = scalarListsCreate,
      scalarListUpdate = scalarListsUpdate,
      createCheck = createCheck
    )
    .map(mapToUnitResult)

  override val errorMapper = {
    // https://dev.mysql.com/doc/refman/5.5/en/error-messages-server.html#error_er_dup_entry
    case e: PSQLException if e.getSQLState == "23505" && getFieldOption(mutaction.createNonListArgs.keys ++ mutaction.updateNonListArgs.keys, e).isDefined =>
      APIErrors.UniqueConstraintViolation(mutaction.createPath.lastModel.name,
                                          getFieldOption(mutaction.createNonListArgs.keys ++ mutaction.updateNonListArgs.keys, e).get)

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1452 =>
      APIErrors.NodeDoesNotExist("") //todo

    case e: SQLIntegrityConstraintViolationException if e.getErrorCode == 1048 =>
      APIErrors.FieldCannotBeNull()

    case e: PSQLException if relationChecker.causedByThisMutaction(e.getMessage) =>
      throw RequiredRelationWouldBeViolated(project, mutaction.createPath.lastRelation_!)
  }
}

case class VerifyConnectionInterpreter(mutaction: VerifyConnection)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val project = mutaction.project
  val path    = mutaction.path
  val causeString = path.lastEdge_! match {
    case _: ModelEdge => s"CONNECTIONFAILURETRIGGERPATH@${path.lastRelation_!.relationTableName}@${path.parentSideOfLastEdge}"
    case edge: NodeEdge =>
      s"CONNECTIONFAILURETRIGGERPATH@${path.lastRelation_!.relationTableName}@${path.parentSideOfLastEdge}@${path.childSideOfLastEdge}@${edge.childWhere.fieldValueAsString}}"
  }

  override val action = PostGresApiDatabaseMutationBuilder.connectionFailureTrigger(project, path, causeString).map(mapToUnitResult)

  override val errorMapper = {
    case e: PSQLException if e.getMessage.contains(causeString) => throw APIErrors.NodesNotConnectedError(path)
  }
}

case class VerifyWhereInterpreter(mutaction: VerifyWhere)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  val project     = mutaction.project
  val where       = mutaction.where
  val causeString = s"WHEREFAILURETRIGGER@${where.model.name}@${where.field.name}@${where.fieldValueAsString}"

  override val action = PostGresApiDatabaseMutationBuilder.whereFailureTrigger(project, where, causeString).map(mapToUnitResult)

  override val errorMapper = {
    case e: PSQLException if e.getMessage.contains(causeString) => throw APIErrors.NodeNotFoundForWhereError(where)
  }
}

case class CreateDataItemsImportInterpreter(mutaction: CreateDataItemsImport)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  override val action = PostGresApiDatabaseMutationBuilder.createDataItemsImport(mutaction).map(mapToUnitResult)
}

case class CreateRelationRowsImportInterpreter(mutaction: CreateRelationRowsImport)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  override val action = PostGresApiDatabaseMutationBuilder.createRelationRowsImport(mutaction).map(mapToUnitResult)
}

case class PushScalarListsImportInterpreter(mutaction: PushScalarListsImport)(implicit val ec: ExecutionContext) extends DatabaseMutactionInterpreter {
  override val action = PostGresApiDatabaseMutationBuilder.pushScalarListsImport(mutaction).map(mapToUnitResult)
}
