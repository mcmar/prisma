package com.prisma.api.connector.postgresql.database

import java.sql.{PreparedStatement, Statement}

import com.prisma.api.connector.Types.DataItemFilterCollection
import com.prisma.api.connector._
import com.prisma.api.connector.postgresql.database.JdbcExtensions._
import com.prisma.api.connector.postgresql.database.SlickExtensions._
import com.prisma.api.schema.UserFacingError
import com.prisma.gc_values.{GCValue, GCValueExtractor, ListGCValue, NullGCValue}
import com.prisma.shared.models.Manifestations.RelationTableManifestation
import com.prisma.shared.models._
import cool.graph.cuid.Cuid
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.MySQLProfile.api._
import slick.jdbc.SQLActionBuilder
import slick.sql.SqlStreamingAction

import scala.concurrent.ExecutionContext

object PostGresApiDatabaseMutationBuilder {
  // region CREATE

  def createDataItem(projectId: String, path: Path, args: PrismaArgs)(implicit ec: ExecutionContext): SimpleDBIO[CreateDataItemResult] =
    SimpleDBIO[CreateDataItemResult] { x =>
      val argsAsRoot   = args.raw.asRoot
      val fields       = path.lastModel.fields.filter(field => argsAsRoot.hasArgFor(field.name))
      val columns      = fields.map(_.dbName)
      val escapedKeys  = columns.map(column => s""""$column"""").mkString(",")
      val placeHolders = columns.map(_ => "?").mkString(",")

      val query                         = s"""INSERT INTO "$projectId"."${path.lastModel.dbName}" ($escapedKeys) VALUES ($placeHolders)"""
      val itemInsert: PreparedStatement = x.connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)

      fields.map(_.name).zipWithIndex.foreach {
        case (column, index) =>
          argsAsRoot.map.get(column) match {
            case Some(NullGCValue) if column == "createdAt" || column == "updatedAt" => itemInsert.setTimestamp(index + 1, currentTimeStampUTC)
            case Some(gCValue)                                                       => itemInsert.setGcValue(index + 1, gCValue)
            case None if column == "createdAt" || column == "updatedAt"              => itemInsert.setTimestamp(index + 1, currentTimeStampUTC)
            case None                                                                => itemInsert.setNull(index + 1, java.sql.Types.NULL)
          }
      }
      itemInsert.execute()

      val generatedKeys = itemInsert.getGeneratedKeys()
      generatedKeys.next()
      // fixme: the name of the id column might be different
      val field = path.lastModel.getFieldByName_!("id")
      CreateDataItemResult(generatedKeys.getGcValue(field.name, field.typeIdentifier))
    }

  def createRelayRow(projectId: String, path: Path)(implicit ec: ExecutionContext): DBIOAction[UnitDatabaseMutactionResult.type, NoStream, Effect] = {
    val where  = path.lastCreateWhere_!
    val action = sql"""INSERT INTO "#$projectId"."_RelayId" ("id", "stableModelIdentifier") VALUES (${where.fieldValue}, ${where.model.stableIdentifier})"""
    action.asUpdate.map(mapToUnitResult)
  }

  def createRelationRowByPath(projectId: String, path: Path)(implicit ec: ExecutionContext): DBIOAction[DatabaseMutactionResult, NoStream, Effect] = {
    val relation = path.lastRelation_!
    require(!relation.isInlineRelation)

    val childWhere = path.lastEdge_! match {
      case _: ModelEdge   => sys.error("Needs to be a node edge.")
      case edge: NodeEdge => edge.childWhere
    }

    val action = if (relation.hasManifestation) {
      val nodeEdge        = path.lastEdge_!.asInstanceOf[NodeEdge]
      val parentModel     = nodeEdge.parent
      val childModel      = nodeEdge.child
      val manifestation   = relation.manifestation.get.asInstanceOf[RelationTableManifestation]
      val columnForParent = if (parentModel.id == relation.modelAId) manifestation.modelAColumn else manifestation.modelBColumn
      val columnForChild  = if (childModel.id == relation.modelAId) manifestation.modelAColumn else manifestation.modelBColumn

      (sql"""insert into "#$projectId"."#${path.lastRelation_!.relationTableName}" ("#$columnForParent", "#$columnForChild")""" ++
        sql"""Select """ ++ pathQueryForLastChild(projectId, path.removeLastEdge) ++ sql"," ++
        sql""""id" FROM "#$projectId"."#${childWhere.model.dbName}" where "#${childWhere.field.name}" = ${childWhere.fieldValue}""").asUpdate
    } else {
      val relationId = Cuid.createCuid()
      (sql"""insert into "#$projectId"."#${path.lastRelation_!.relationTableName}" ("id", "#${path.parentSideOfLastEdge}", "#${path.childSideOfLastEdge}")""" ++
        sql"""Select '#$relationId',""" ++ pathQueryForLastChild(projectId, path.removeLastEdge) ++ sql"," ++
        sql""""id" FROM "#$projectId"."#${childWhere.model.name}" where "#${childWhere.field.name}" = ${childWhere.fieldValue}""").asUpdate
    }
    action.map(mapToUnitResult)

//    (sql"""update "#$projectId".todo""" ++
//      sql"""set list_id = subquery.id""" ++
//      sql"""from (select "id" from "#$projectId"."#${childWhere.model.pgTableName}" where "#${childWhere.field.name}" = ${childWhere.fieldValue}) as subquery""" ++
//      sql"""where "#$projectId".todo.id = """ ++ pathQueryForLastChild(projectId, path.removeLastEdge)).asUpdate

//    https://stackoverflow.com/questions/1109061/insert-on-duplicate-update-in-postgresql
//    ++
//      sql"on conflict (id )  key update #$projectId.#${path.lastRelation_!.relationTableName}.id = #$projectId.#${path.lastRelation_!.relationTableName}.id").asUpdate
  }

  //endregion

  //region UPDATE

  def updateDataItems(projectId: String, model: Model, args: PrismaArgs, whereFilter: Option[DataItemFilterCollection])(implicit ec: ExecutionContext) = {
    val updateValues = combineByComma(args.raw.asRoot.map.map { case (k, v) => escapeKey(k) ++ sql" = $v" })

    if (updateValues.isDefined) {
      (sql"""UPDATE "#${projectId}"."#${model.name}"""" ++ sql"SET " ++ updateValues ++ whereFilterAppendix(projectId, model.name, whereFilter)).asUpdate
    } else {
      DBIOAction.successful(())
    }
  }

  def updateDataItemByPath(projectId: String, path: Path, updateArgs: PrismaArgs)(implicit ec: ExecutionContext) = {
    val updateValues = combineByComma(updateArgs.raw.asRoot.map.map { case (k, v) => escapeKey(k) ++ sql" = $v" })
    def fromEdge(edge: Edge) = edge match {
      case edge: NodeEdge => sql""" "#${path.childSideOfLastEdge}"""" ++ idFromWhereEquals(projectId, edge.childWhere) ++ sql" AND "
      case _: ModelEdge   => sql""
    }

    val baseQuery = sql"""UPDATE "#${projectId}"."#${path.lastModel.name}" SET """ ++ updateValues ++ sql"""WHERE "id" ="""

    if (updateArgs.raw.asRoot.map.isEmpty) {
      DBIOAction.successful(())
    } else {
      val query = path.lastEdge match {
        case Some(edge) =>
          baseQuery ++ sql"""(SELECT "#${path.childSideOfLastEdge}" """ ++
            sql"""FROM "#${projectId}"."#${path.lastRelation_!.relationTableName}"""" ++
            sql"WHERE" ++ fromEdge(edge) ++ sql""""#${path.parentSideOfLastEdge}" = """ ++ pathQueryForLastParent(projectId, path) ++ sql")"
        case None => baseQuery ++ idFromWhere(projectId, path.root)
      }
      query.asUpdate
    }
  }

  //endregion

  //region UPSERT

  def upsert(projectId: String,
             createPath: Path,
             updatePath: Path,
             createArgs: PrismaArgs,
             updateArgs: PrismaArgs,
             create: slick.dbio.DBIOAction[_, slick.dbio.NoStream, slick.dbio.Effect.All],
             update: slick.dbio.DBIOAction[_, slick.dbio.NoStream, slick.dbio.Effect.All])(implicit ec: ExecutionContext) = {

    val query = sql"""select exists ( SELECT "id" FROM "#$projectId"."#${updatePath.lastModel.name}" WHERE "id" = """ ++ pathQueryForLastChild(
      projectId,
      updatePath) ++ sql")"
    val condition = query.as[Boolean]
    // insert creates item first, then the list values
    val qInsert = DBIOAction.seq(createDataItem(projectId, createPath, createArgs), createRelayRow(projectId, createPath), create)
    // update first sets the lists, then updates the item
    val qUpdate = DBIOAction.seq(update, updateDataItemByPath(projectId, updatePath, updateArgs))

    ifThenElse(condition, qUpdate, qInsert)
  }

  def upsertIfInRelationWith(
      project: Project,
      createPath: Path,
      updatePath: Path,
      createArgs: PrismaArgs,
      updateArgs: PrismaArgs,
      scalarListCreate: slick.dbio.DBIOAction[_, slick.dbio.NoStream, slick.dbio.Effect.All],
      scalarListUpdate: slick.dbio.DBIOAction[_, slick.dbio.NoStream, slick.dbio.Effect.All],
      createCheck: DBIOAction[Any, NoStream, Effect],
  )(implicit ec: ExecutionContext) = {

    def existsNodeIsInRelationshipWith = {
      def nodeSelector(last: Edge) = last match {
        case edge: NodeEdge => sql" id" ++ idFromWhereEquals(project.id, edge.childWhere) ++ sql" AND "
        case _: ModelEdge   => sql""
      }

      sql"""select EXISTS (
            select "id" from "#${project.id}"."#${updatePath.lastModel.name}"
            where""" ++ nodeSelector(updatePath.lastEdge_!) ++
        sql""" "id" IN""" ++ PostGresApiDatabaseMutationBuilder.pathQueryThatUsesWholePath(project.id, updatePath) ++ sql")"
    }

    val condition = existsNodeIsInRelationshipWith.as[Boolean]
    //insert creates item first and then the listvalues
    val qInsert = DBIOAction.seq(createDataItem(project.id, createPath, createArgs), createRelayRow(project.id, createPath), createCheck, scalarListCreate)
    //update updates list values first and then the item
    val qUpdate = DBIOAction.seq(scalarListUpdate, updateDataItemByPath(project.id, updatePath, updateArgs))

    ifThenElseNestedUpsert(condition, qUpdate, qInsert)
  }

  //endregion

  //region DELETE

  def deleteDataItems(project: Project, model: Model, whereFilter: Option[DataItemFilterCollection])(implicit ec: ExecutionContext) = {
    (sql"""DELETE FROM "#${project.id}"."#${model.name}"""" ++ whereFilterAppendix(project.id, model.name, whereFilter)).asUpdate
  }

  def deleteRelayIds(project: Project, model: Model, whereFilter: Option[DataItemFilterCollection])(implicit ec: ExecutionContext) = {
    (sql"""DELETE FROM "#${project.id}"."_RelayId" WHERE "id" IN ( SELECT "id" FROM "#${project.id}"."#${model.name}"""" ++ whereFilterAppendix(
      project.id,
      model.name,
      whereFilter) ++ sql")").asUpdate
  }

  def deleteDataItem(projectId: String, path: Path)(implicit ec: ExecutionContext) =
    (sql"""DELETE FROM "#$projectId"."#${path.lastModel.name}" WHERE "id" = """ ++ pathQueryForLastChild(projectId, path)).asUpdate

  def deleteRelayRow(projectId: String, path: Path)(implicit ec: ExecutionContext) =
    (sql"""DELETE FROM "#$projectId"."_RelayId" WHERE "id" = """ ++ pathQueryForLastChild(projectId, path)).asUpdate

  def deleteRelationRowByParent(projectId: String, path: Path)(implicit ec: ExecutionContext) = {
    (sql"""DELETE FROM "#$projectId"."#${path.lastRelation_!.relationTableName}" WHERE "#${path.parentSideOfLastEdge}" = """ ++ pathQueryForLastParent(
      projectId,
      path)).asUpdate
  }

  def deleteRelationRowByChildWithWhere(projectId: String, path: Path)(implicit ec: ExecutionContext) = {
    val where = path.lastEdge_! match {
      case _: ModelEdge   => sys.error("Should be a node Edge")
      case edge: NodeEdge => edge.childWhere

    }
    (sql"""DELETE FROM "#$projectId"."#${path.lastRelation_!.relationTableName}" WHERE "#${path.childSideOfLastEdge}"""" ++ idFromWhereEquals(projectId, where)).asUpdate
  }

  def deleteRelationRowByParentAndChild(projectId: String, path: Path)(implicit ec: ExecutionContext) = {
    (sql"""DELETE FROM "#$projectId"."#${path.lastRelation_!.relationTableName}" """ ++
      sql"""WHERE "#${path.childSideOfLastEdge}" = """ ++ pathQueryForLastChild(projectId, path) ++
      sql""" AND "#${path.parentSideOfLastEdge}" = """ ++ pathQueryForLastParent(projectId, path)).asUpdate
  }

  def cascadingDeleteChildActions(projectId: String, path: Path)(implicit ec: ExecutionContext) = {
    val deleteRelayIds = (sql"""DELETE FROM "#$projectId"."_RelayId" WHERE "id" IN (""" ++ pathQueryForLastChild(projectId, path) ++ sql")").asUpdate
    val deleteDataItems =
      (sql"""DELETE FROM "#$projectId"."#${path.lastModel.name}" WHERE "id" IN (""" ++ pathQueryForLastChild(projectId, path) ++ sql")").asUpdate
    DBIO.seq(deleteRelayIds, deleteDataItems)
  }

  //endregion

  //region SCALAR LISTS
  def setScalarList(
      projectId: String,
      path: Path,
      listFieldMap: Vector[(String, ListGCValue)]
  )(implicit ec: ExecutionContext): DBIOAction[UnitDatabaseMutactionResult.type, NoStream, Effect.All] = {
    val idQuery = (sql"""SELECT "id" FROM "#${projectId}"."#${path.lastModel.name}" WHERE "id" = """ ++ pathQueryForLastChild(projectId, path)).as[String]
    val action  = if (listFieldMap.isEmpty) DBIOAction.successful(()) else setManyScalarListHelper(projectId, path.lastModel, listFieldMap, idQuery)
    action.map(mapToUnitResult)
  }

  def setManyScalarLists(projectId: String, model: Model, listFieldMap: Vector[(String, ListGCValue)], whereFilter: Option[DataItemFilterCollection])(
      implicit ec: ExecutionContext) = {
    val idQuery = (sql"""SELECT "id" FROM "#${projectId}"."#${model.name}"""" ++ whereFilterAppendix(projectId, model.name, whereFilter)).as[String]
    if (listFieldMap.isEmpty) DBIOAction.successful(()) else setManyScalarListHelper(projectId, model, listFieldMap, idQuery)
  }

  def setManyScalarListHelper(projectId: String,
                              model: Model,
                              listFieldMap: Vector[(String, ListGCValue)],
                              idQuery: SqlStreamingAction[Vector[String], String, Effect])(implicit ec: ExecutionContext) = {

    def listInsert(ids: Vector[String]) = {
      if (ids.isEmpty) {
        DBIOAction.successful(())
      } else {

        SimpleDBIO[Unit] { x =>
          def valueTuplesForListField(listGCValue: ListGCValue) =
            for {
              nodeId                   <- ids
              (escapedValue, position) <- listGCValue.values.zip((1 to listGCValue.size).map(_ * 1000))
            } yield {
              (nodeId, position, escapedValue)
            }

          val whereString = ids.length match {
            case 1 => s""" WHERE "nodeId" =  '${ids.head}'"""
            case _ => s""" WHERE "nodeId" in ${ids.map(id => s"'$id'").mkString("(", ",", ")")}"""
          }

          listFieldMap.foreach {
            case (fieldName, listGCValue) =>
              val wipe                             = s"""DELETE  FROM "$projectId"."${model.name}_$fieldName" $whereString"""
              val wipeOldValues: PreparedStatement = x.connection.prepareStatement(wipe)
              wipeOldValues.executeUpdate()

              val insert                             = s"""INSERT INTO "$projectId"."${model.name}_$fieldName" ("nodeId", "position", "value") VALUES (?,?,?)"""
              val insertNewValues: PreparedStatement = x.connection.prepareStatement(insert)
              val newValueTuples                     = valueTuplesForListField(listGCValue)
              newValueTuples.foreach { tuple =>
                insertNewValues.setString(1, tuple._1)
                insertNewValues.setInt(2, tuple._2)
                insertNewValues.setGcValue(3, tuple._3)
                insertNewValues.addBatch()
              }
              insertNewValues.executeBatch()
          }
        }
      }
    }

    for {
      nodeIds <- idQuery
      action  <- listInsert(nodeIds)
    } yield action
  }

  //endregion

  //region RESET DATA
  def truncateTable(projectId: String, tableName: String)(implicit ec: ExecutionContext) = sqlu"""TRUNCATE TABLE "#$projectId"."#$tableName" CASCADE"""

  //endregion

  // region HELPERS

  def idFromWhere(projectId: String, where: NodeSelector)(implicit ec: ExecutionContext): SQLActionBuilder = {
    if (where.isId) {
      sql"""${where.fieldValue}"""
    } else {
      sql"""(SELECT "id" FROM (SELECT * FROM "#$projectId"."#${where.model.name}") IDFROMWHERE WHERE "#${where.field.name}" = ${where.fieldValue})"""
    }
  }

  def idFromWhereEquals(projectId: String, where: NodeSelector)(implicit ec: ExecutionContext): SQLActionBuilder = sql" = " ++ idFromWhere(projectId, where)

  def idFromWherePath(projectId: String, where: NodeSelector)(implicit ec: ExecutionContext): SQLActionBuilder = {
    sql"""(SELECT "id" FROM (SELECT  * From "#$projectId"."#${where.model.name}") IDFROMWHEREPATH WHERE "#${where.field.name}" = ${where.fieldValue})"""
  }

  //we could probably save even more joins if we start the paths always at the last node edge

  def pathQueryForLastParent(projectId: String, path: Path)(implicit ec: ExecutionContext): SQLActionBuilder =
    pathQueryForLastChild(projectId, path.removeLastEdge)

  def pathQueryForLastChild(projectId: String, path: Path)(implicit ec: ExecutionContext): SQLActionBuilder = {
    path.edges match {
      case Nil                                => idFromWhere(projectId, path.root)
      case x if x.last.isInstanceOf[NodeEdge] => idFromWhere(projectId, x.last.asInstanceOf[NodeEdge].childWhere)
      case _                                  => pathQueryThatUsesWholePath(projectId, path)
    }
  }

  object ::> { def unapply[A](l: List[A]) = Some((l.init, l.last)) }

  def pathQueryThatUsesWholePath(projectId: String, path: Path)(implicit ec: ExecutionContext): SQLActionBuilder = {
    path.edges match {
      case Nil =>
        idFromWherePath(projectId, path.root)

      case _ ::> last =>
        val childWhere = last match {
          case edge: NodeEdge => sql""" "#${edge.childRelationSide}"""" ++ idFromWhereEquals(projectId, edge.childWhere) ++ sql" AND "
          case _: ModelEdge   => sql""
        }

        sql"""(SELECT "#${last.childRelationSide}"""" ++
          sql""" FROM (SELECT * FROM "#$projectId"."#${last.relation.relationTableName}") PATHQUERY""" ++
          sql" WHERE " ++ childWhere ++ sql""""#${last.parentRelationSide}" IN (""" ++ pathQueryForLastParent(projectId, path) ++ sql"))"
    }
  }

  def whereFailureTrigger(project: Project, where: NodeSelector, causeString: String)(implicit ec: ExecutionContext) = {
    val table = where.model.name
    val query = sql"""(SELECT "id" FROM "#${project.id}"."#${where.model.name}" WHEREFAILURETRIGGER WHERE "#${where.field.name}" = ${where.fieldValue})"""

    triggerFailureWhenNotExists(project, query, table, causeString)
  }

  def connectionFailureTrigger(project: Project, path: Path, causeString: String)(implicit ec: ExecutionContext) = {
    val table = path.lastRelation.get.relationTableName

    val lastChildWhere = path.lastEdge_! match {
      case edge: NodeEdge => sql""" "#${path.childSideOfLastEdge}"""" ++ idFromWhereEquals(project.id, edge.childWhere) ++ sql" AND "
      case _: ModelEdge   => sql""
    }

    val query =
      sql"""SELECT "id" FROM "#${project.id}"."#$table" CONNECTIONFAILURETRIGGERPATH""" ++
        sql"WHERE" ++ lastChildWhere ++ sql""""#${path.parentSideOfLastEdge}" = """ ++ pathQueryForLastParent(project.id, path)

    triggerFailureWhenNotExists(project, query, table, causeString)
  }

  def oldParentFailureTriggerForRequiredRelations(
      project: Project,
      relation: Relation,
      where: NodeSelector,
      childSide: RelationSide.Value,
      triggerString: String)(implicit ec: ExecutionContext): slick.sql.SqlStreamingAction[Vector[String], String, slick.dbio.Effect] = {
    val table = relation.relationTableName
    val query = sql"""SELECT "id" FROM "#${project.id}"."#$table" OLDPARENTFAILURETRIGGER WHERE "#$childSide" """ ++ idFromWhereEquals(project.id, where)

    triggerFailureWhenExists(project, query, table, triggerString)
  }

  def oldParentFailureTrigger(project: Project, path: Path, triggerString: String)(implicit ec: ExecutionContext) = {
    val table = path.lastRelation_!.relationTableName
    val query = sql"""SELECT "id" FROM "#${project.id}"."#$table" OLDPARENTPATHFAILURETRIGGER WHERE "#${path.childSideOfLastEdge}" IN (""" ++ pathQueryForLastChild(
      project.id,
      path) ++ sql")"
    triggerFailureWhenExists(project, query, table, triggerString)
  }

  def oldParentFailureTriggerByField(project: Project, path: Path, field: Field, triggerString: String)(implicit ec: ExecutionContext) = {
    val table = field.relation.get.relationTableName
    val query = sql"""SELECT "id" FROM "#${project.id}"."#$table" OLDPARENTPATHFAILURETRIGGERBYFIELD WHERE "#${field.oppositeRelationSide.get}" IN (""" ++ pathQueryForLastChild(
      project.id,
      path) ++ sql")"
    triggerFailureWhenExists(project, query, table, triggerString)
  }

  def oldParentFailureTriggerByFieldAndFilter(project: Project, model: Model, whereFilter: Option[DataItemFilterCollection], field: Field, causeString: String)(
      implicit ec: ExecutionContext) = {
    val table = field.relation.get.relationTableName
    val query = sql"""SELECT "id" FROM "#${project.id}"."#$table" OLDPARENTPATHFAILURETRIGGERBYFIELDANDFILTER""" ++
      sql"""WHERE "#${field.oppositeRelationSide.get}" IN (SELECT "id" FROM "#${project.id}"."#${model.name}" """ ++
      whereFilterAppendix(project.id, model.name, whereFilter) ++ sql")"
    triggerFailureWhenExists(project, query, table, causeString)
  }

  def oldChildFailureTrigger(project: Project, path: Path, triggerString: String)(implicit ec: ExecutionContext) = {
    val table = path.lastRelation_!.relationTableName
    val query = sql"""SELECT "id" FROM "#${project.id}"."#$table" OLDCHILDPATHFAILURETRIGGER WHERE "#${path.parentSideOfLastEdge}" IN (""" ++ pathQueryForLastParent(
      project.id,
      path) ++ sql")"
    triggerFailureWhenExists(project, query, table, triggerString)
  }

  def ifThenElse(condition: SqlStreamingAction[Vector[Boolean], Boolean, Effect],
                 thenMutactions: DBIOAction[_, NoStream, Effect.All],
                 elseMutactions: DBIOAction[_, NoStream, Effect.All])(implicit ec: ExecutionContext) = {
    for {
      exists <- condition
      action <- if (exists.head) thenMutactions else elseMutactions
    } yield action
  }

  def ifThenElseNestedUpsert(condition: SqlStreamingAction[Vector[Boolean], Boolean, Effect],
                             thenMutactions: DBIOAction[Unit, NoStream, Effect.All],
                             elseMutactions: DBIOAction[Unit, NoStream, Effect.All])(implicit ec: ExecutionContext) = {
    for {
      exists <- condition
      action <- if (exists.head) thenMutactions else elseMutactions
    } yield action
  }

  def ifThenElseError(condition: SqlStreamingAction[Vector[Boolean], Boolean, Effect],
                      thenMutactions: DBIOAction[Unit, NoStream, Effect],
                      elseError: UserFacingError)(implicit ec: ExecutionContext) = {
    for {
      exists <- condition
      action <- if (exists.head) thenMutactions else throw elseError
    } yield action
  }
  def triggerFailureWhenExists(project: Project, query: SQLActionBuilder, table: String, triggerString: String)(implicit ec: ExecutionContext) =
    triggerFailureInternal(project, query, table, triggerString, notExists = false)
  def triggerFailureWhenNotExists(project: Project, query: SQLActionBuilder, table: String, triggerString: String)(implicit ec: ExecutionContext) =
    triggerFailureInternal(project, query, table, triggerString, notExists = true)

  private def triggerFailureInternal(project: Project, query: SQLActionBuilder, table: String, triggerString: String, notExists: Boolean)(
      implicit ec: ExecutionContext) = {
    val notValue = if (notExists) s"" else s"not"

    (sql"select case when #$notValue exists ( " ++ query ++ sql" )" ++
      sql"then '' " ++
      sql"else (raise_exception($triggerString))end;").as[String]
  }

  //endregion

  def createDataItemsImport(mutaction: CreateDataItemsImport)(implicit ec: ExecutionContext): SimpleDBIO[Vector[String]] = {

    SimpleDBIO[Vector[String]] { x =>
      val model         = mutaction.model
      val argsWithIndex = mutaction.args.zipWithIndex

      val nodeResult: Vector[String] = try {
        val columns      = model.scalarNonListFields.map(_.name)
        val escapedKeys  = columns.map(column => s""""$column"""").mkString(",")
        val placeHolders = columns.map(_ => "?").mkString(",")

        val query                         = s"""INSERT INTO "${mutaction.project.id}"."${model.name}" ($escapedKeys) VALUES ($placeHolders)"""
        val itemInsert: PreparedStatement = x.connection.prepareStatement(query)
        val currentTimeStamp              = currentTimeStampUTC

        mutaction.args.foreach { arg =>
          columns.zipWithIndex.foreach { columnAndIndex =>
            val index  = columnAndIndex._2 + 1
            val column = columnAndIndex._1

            arg.raw.asRoot.map.get(column) match {
              case Some(x)                                                => itemInsert.setGcValue(index, x)
              case None if column == "createdAt" || column == "updatedAt" => itemInsert.setTimestamp(index, currentTimeStamp)
              case None                                                   => itemInsert.setNull(index, java.sql.Types.NULL)
            }
          }
          itemInsert.addBatch()
        }

        itemInsert.executeBatch()

        Vector.empty
      } catch {
        case e: java.sql.BatchUpdateException =>
          e.getUpdateCounts.zipWithIndex
            .filter(element => element._1 == Statement.EXECUTE_FAILED)
            .map { failed =>
              val failedId = argsWithIndex.find(_._2 == failed._2).get._1.raw.asRoot.idField.value
              s"Failure inserting ${model.name} with Id: $failedId. Cause: ${removeConnectionInfoFromCause(e.getCause.toString)}"
            }
            .toVector
        case e: Exception => Vector(e.getCause.toString)
      }

      val relayResult: Vector[String] = try {
        val relayQuery                     = s"""INSERT INTO "${mutaction.project.id}"."_RelayId" ("id", "stableModelIdentifier") VALUES (?,?)"""
        val relayInsert: PreparedStatement = x.connection.prepareStatement(relayQuery)

        mutaction.args.foreach { arg =>
          relayInsert.setString(1, arg.raw.asRoot.idField.value)
          relayInsert.setString(2, model.stableIdentifier)
          relayInsert.addBatch()
        }
        relayInsert.executeBatch()

        Vector.empty
      } catch {
        case e: java.sql.BatchUpdateException =>
          e.getUpdateCounts.zipWithIndex
            .filter(element => element._1 == Statement.EXECUTE_FAILED)
            .map { failed =>
              val failedId = argsWithIndex.find(_._2 == failed._2).get._1.raw.asRoot.idField.value
              s"Failure inserting RelayRow with Id: $failedId. Cause: ${removeConnectionInfoFromCause(e.getCause.toString)}"
            }
            .toVector
        case e: Exception => Vector(e.getCause.toString)
      }

      val res = nodeResult ++ relayResult
      if (res.nonEmpty) throw new Exception(res.mkString("-@-"))
      res
    }
  }

  def removeConnectionInfoFromCause(cause: String): String = {
    val connectionSubStringStart = cause.indexOf(": ERROR:")
    cause.substring(connectionSubStringStart + 9)

  }

  def createRelationRowsImport(mutaction: CreateRelationRowsImport)(implicit ec: ExecutionContext): SimpleDBIO[Vector[String]] = {
    val argsWithIndex: Seq[((String, String), Int)] = mutaction.args.zipWithIndex

    SimpleDBIO[Vector[String]] { x =>
      val res = try {
        val query                             = s"""INSERT INTO "${mutaction.project.id}"."${mutaction.relation.relationTableName}" ("id", "A","B") VALUES (?,?,?)"""
        val relationInsert: PreparedStatement = x.connection.prepareStatement(query)
        mutaction.args.foreach { arg =>
          relationInsert.setString(1, Cuid.createCuid())
          relationInsert.setString(2, arg._1)
          relationInsert.setString(3, arg._2)
          relationInsert.addBatch()
        }
        relationInsert.executeBatch()
        Vector.empty
      } catch {
        case e: java.sql.BatchUpdateException =>
          val faileds = e.getUpdateCounts.zipWithIndex

          faileds
            .filter(element => element._1 == Statement.EXECUTE_FAILED)
            .map { failed =>
              val failedA = argsWithIndex.find(_._2 == failed._2).get._1._1
              val failedB = argsWithIndex.find(_._2 == failed._2).get._1._2
              s"Failure inserting into relationtable ${mutaction.relation.relationTableName} with ids $failedA and $failedB. Cause: ${removeConnectionInfoFromCause(
                e.getCause.toString)}"
            }
            .toVector
        case e: Exception =>
          println(e.getMessage)
          Vector(e.getMessage)
      }

      if (res.nonEmpty) throw new Exception(res.mkString("-@-"))
      res
    }
  }

  def pushScalarListsImport(mutaction: PushScalarListsImport)(implicit ec: ExecutionContext) = {

    val projectId = mutaction.project.id
    val tableName = mutaction.tableName
    val nodeId    = mutaction.id

    val idQuery =
      sql"""Select "case" from (
            Select max("position"),
            CASE WHEN max("position") IS NULL THEN 1000
            ELSE max("position") +1000
            END
            FROM "#$projectId"."#$tableName"
            WHERE "nodeId" = $nodeId
            ) as "ALIAS"
      """.as[Int]

    def pushQuery(baseLine: Int) = SimpleDBIO[Vector[String]] { x =>
      val argsWithIndex = mutaction.args.values.zipWithIndex
      val rowResult: Vector[String] = try {
        val query                         = s"""insert into "$projectId"."$tableName" ("nodeId", "position", "value") values (?, $baseLine + ? , ?)"""
        val insertRows: PreparedStatement = x.connection.prepareStatement(query)

        argsWithIndex.foreach { argWithIndex =>
          insertRows.setString(1, nodeId)
          insertRows.setInt(2, argWithIndex._2 * 1000)
          insertRows.setGcValue(3, argWithIndex._1)
          insertRows.addBatch()
        }
        insertRows.executeBatch()

        Vector.empty
      } catch {
        case e: java.sql.BatchUpdateException =>
          e.getUpdateCounts.zipWithIndex
            .filter(element => element._1 == Statement.EXECUTE_FAILED)
            .map { failed =>
              val failedValue: GCValue = argsWithIndex.find(_._2 == failed._2).get._1
              s"Failure inserting into listTable $tableName for the id $nodeId for value ${GCValueExtractor
                .fromGCValue(failedValue)}. Cause: ${removeConnectionInfoFromCause(e.getCause.toString)}"
            }
            .toVector

        case e: Exception =>
          println(e.getMessage)
          Vector(e.getMessage)
      }

      if (rowResult.nonEmpty) throw new Exception(rowResult.mkString("-@-"))
      rowResult
    }

    for {
      nodeIds <- idQuery
      action  <- pushQuery(nodeIds.head)
    } yield action
  }

  /**
    * HELPERS
    */
  def mapToUnitResult(x: Any): UnitDatabaseMutactionResult.type = UnitDatabaseMutactionResult
}
