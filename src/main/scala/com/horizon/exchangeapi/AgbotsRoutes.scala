/** Services routes for all of the /agbots api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
// import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.scalatra.json._
import org.slf4j._
import Access._
import BaseAccess._
import scala.util._
import scala.util.control.Breaks._
import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, Set => MutableSet, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import com.horizon.exchangeapi.tables._

//====== These are the input and output structures for /agbots routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /agbots */
case class GetAgbotsResponse(agbots: Map[String,Agbot], lastIndex: Int)

/** Input format for PUT /agbots/<agbot-id> */
case class PutAgbotsRequest(token: String, name: String, msgEndPoint: String) {
  /* Puts this entry in the database */
  def copyToTempDb(id: String, owner: String) = {
    var tok = if (token == "") "" else Password.hash(token)
    var own = owner
    TempDb.agbots.get(id) match {
      // If the agbot exists and token in body is "" then do not overwrite token in the db entry
      case Some(agbot) => if (token == "") tok = agbot.token              // do not need to hash it because it already is
        if (owner == "" || Role.isSuperUser(owner)) own = agbot.owner
      case None => ;
    }
    val dbAgbot = new Agbot(tok, name, own, msgEndPoint, ApiTime.nowUTC)
    TempDb.agbots.put(id, dbAgbot)
    if (token != "") AuthCache.agbots.put(Creds(id, token))    // the token passed in to the cache should be the non-hashed one
  }

  /** Get the db queries to insert or update the agbot */
  def getDbUpsert(id: String, owner: String): DBIO[_] = AgbotRow(id, token, name, owner, msgEndPoint, ApiTime.nowUTC).upsert

  /** Get the db queries to update the agbot */
  def getDbUpdate(id: String, owner: String): DBIO[_] = AgbotRow(id, token, name, owner, msgEndPoint, ApiTime.nowUTC).update
}


/** Output format for GET /agbots/{id}/agreements */
case class GetAgbotAgreementsResponse(agreements: Map[String,AgbotAgreement], lastIndex: Int)

/** Input format for PUT /agbots/{id}/agreements/<agreement-id> */
case class PutAgbotAgreementRequest(workload: String, state: String) {
  /* Puts this entry in the database */
  def copyToTempDb(agbotid: String, agid: String) = {
    TempDb.agbotsAgreements.get(agbotid) match {
      case Some(agbotValue) => agbotValue.put(agid, AgbotAgreement(workload, state, ApiTime.nowUTC, ""))
      case None => val agbotValue = new MutableHashMap[String,AgbotAgreement]() += ((agid, AgbotAgreement(workload, state, ApiTime.nowUTC, "")))
        TempDb.agbotsAgreements += ((agbotid, agbotValue))    // this devid is not already in the db, add it
    }
  }

  def toAgbotAgreement = AgbotAgreement(workload, state, ApiTime.nowUTC, "")
  def toAgbotAgreementRow(agbotId: String, agrId: String) = AgbotAgreementRow(agrId, agbotId, workload, state, ApiTime.nowUTC, "")
}

case class PostAgbotsIsRecentDataRequest(secondsStale: Int, agreementIds: List[String])     // the strings in the list are agreement ids
case class PostAgbotsIsRecentDataElement(agreementId: String, recentData: Boolean)

case class PostAgreementsConfirmRequest(agreementId: String) {
  /** Returns true if this agreementId is owned by an agbot owned by this owner, and the agreement is active. */
  def confirmTempDb(owner: String): Boolean = {
    // Find the agreement ids in any of this user's agbots
    val agbotIds = TempDb.agbots.toMap.filter(a => a._2.owner == owner).keys.toSet      // all the agbots owned by this user
    val agbotsAgreements = TempDb.agbotsAgreements.toMap.filter(a => agbotIds.contains(a._1) && a._2.keys.toSet.contains(agreementId))    // agbotsAgreements is hash of hash: 1st key is agbot id, 2nd key is agreement id, value is agreement info
    // we still have the same hash of hash, but have reduced the top level to only agbot ids that contain these agreement ids
    if (agbotsAgreements.size == 0) return false
    // println(agbotsAgreements)

    // Find the state of the agreement
    val agreementMap = agbotsAgreements.values.head     // get the agreement hash that contained this agreement id
    return agreementMap.get(agreementId).get.state != ""
  }
}


/** Implementation for all of the /agbots routes */
trait AgbotsRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /agbots ================================ */
  val getAgbots =
    (apiOperation[GetAgbotsResponse]("getAgbots")
      summary("Returns all agbots")
      notes("""Returns all agbots (Agreement Bots) in the exchange DB. Can be run by any user.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include agbots with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("name", DataType.String, Option[String]("Filter results to only include agbots with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include agbots with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots. Normally called by the user to see all agbots. */
  get("/agbots", operation(getAgbots)) ({
    // logger.info("GET /agbots")
    val creds = validateUserOrAgbotId(BaseAccess.READ, "*")
    val superUser = isSuperUser(creds)
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      var agbots = TempDb.agbots.toMap
      if (!superUser) agbots = agbots.mapValues(a => {val a2 = a.copy; a2.token = "********"; a2})
      GetAgbotsResponse(agbots, 0)
    } else {
      // var q = AgbotsTQ.rows.to[scala.collection.Seq]
      var q = AgbotsTQ.rows.subquery
      // add filters
      // params.get("idfilter") match {
      //   case Some(id) => q = AgbotsTQ.getAgbot(id)
      //   case _ => ;
      // }
      params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
      params.get("name").foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })
      params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })

      db.run(q.result).map({ list =>
        logger.debug("GET /agbots result size: "+list.size)
        val agbots = new MutableHashMap[String,Agbot]
        for (a <- list) agbots.put(a.id, a.toAgbot(superUser))
        GetAgbotsResponse(agbots.toMap, 0)
      })
    }
  })

  /* ====== GET /agbots/{id} ================================ */
  val getOneAgbot =
    (apiOperation[GetAgbotsResponse]("getOneAgbot")
      summary("Returns a agbot")
      notes("""Returns the agbot (Agreement Bot) with the specified id in the exchange DB. Can be run by a user or the agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}. Normally called by the agbot to verify his own entry after a reboot. */
  get("/agbots/:id", operation(getOneAgbot)) ({
    // logger.info("GET /agbots/"+params("id"))
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val creds = validateUserOrAgbotId(BaseAccess.READ, id)
    val superUser = isSuperUser(creds)
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      var agbots = TempDb.agbots.toMap.filter(a => a._1 == id)
      if (!isSuperUser(creds)) agbots = agbots.mapValues(a => {val a2 = a.copy; a2.token = "********"; a2})
      if (!agbots.contains(id)) status_=(HttpCode.NOT_FOUND)
      GetAgbotsResponse(agbots, 0)
    } else {
      db.run(AgbotsTQ.getAgbot(id).result).map({ list =>
        logger.debug("GET /agbots/"+id+" result: "+list.toString)
        val agbots = new MutableHashMap[String,Agbot]
        if (list.size > 0) for (a <- list) agbots.put(a.id, a.toAgbot(superUser))
        else resp.setStatus(HttpCode.NOT_FOUND)
        GetAgbotsResponse(agbots.toMap, 0)
      })
    }
  })

  // =========== PUT /agbots/{id} ===============================
  val putAgbots =
    (apiOperation[ApiResponse]("putAgbots")
      summary "Adds/updates a agbot"
      notes """Adds a new agbot (Agreement Bot) to the exchange DB, or updates an existing agbot. This must be called by the user to add a agbot, and then can be called by that user or agbot to update itself. The **request body** structure:

```
{
  "token": "abc",       // agbot token, set by user when adding this agbot. When the agbot is running this to update the agbot it can set this to the empty string
  "name": "agbot3",         // agbot name that you pick
  "msgEndPoint": "whisper-id"    // msg service endpoint id for this agbot to be contacted by agbots
}
```"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutAgbotsRequest],
          Option[String]("Agbot object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putAgbots2 = (apiOperation[PutAgbotsRequest]("putAgbots2") summary("a") notes("a"))  // for some bizarre reason, the PutAgbotsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /agbot/{id}. Normally called by agbot to add/update itself. */
  put("/agbots/:id", operation(putAgbots)) ({
    // logger.info("PUT /agbots/"+params("id"))
    val id = params("id")
    val baseAccess = if (TempDb.agbots.contains(id)) BaseAccess.WRITE else BaseAccess.CREATE
    val creds = validateUserOrAgbotId(baseAccess, id)
    val agbot = try { parse(request.body).extract[PutAgbotsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      if (baseAccess == BaseAccess.CREATE && TempDb.agbots.filter(d => d._2.owner == owner).size >= ExchConfig.getInt("api.limits.maxAgbots")) {    // make sure they are not trying to overrun the exchange svr by creating a ton of agbots
        status_=(HttpCode.ACCESS_DENIED)
        ApiResponse(ApiResponseType.ACCESS_DENIED, "can not create more than "+ExchConfig.getInt("api.limits.maxAgbots")+ " agbots")
      } else {
        agbot.copyToTempDb(id, owner)
        if (baseAccess == BaseAccess.CREATE && owner != "") logger.info("User '"+owner+"' created agbot '"+id+"'.")
        status_=(HttpCode.PUT_OK)
        ApiResponse(ApiResponseType.OK, "agbot added or updated")
      }
    } else {   // persistence
      //TODO: check they haven't created too many agbots using Await.result
      val resp = response
      val action = if (owner == "") agbot.getDbUpdate(id, owner) else agbot.getDbUpsert(id, owner)
      db.run(action.asTry).map({ xs =>
        logger.debug("PUT /agbots/"+id+" result: "+xs.toString)
        xs match {
          case Success(v) => if (agbot.token != "") AuthCache.agbots.put(Creds(id, agbot.token))    // the token passed in to the cache should be the non-hashed one
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "agbot added or updated")
          case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not inserted or updated: "+t.toString)
        }
      })
    }
  })

  // =========== DELETE /agbots/{id} ===============================
  val deleteAgbots =
    (apiOperation[ApiResponse]("deleteAgbots")
      summary "Deletes a agbot"
      notes "Deletes a agbot (Agreement Bot) from the exchange DB, and deletes the agreements stored for this agbot (but does not actually cancel the agreements between the devices and agbot). Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /agbots/{id}. */
  delete("/agbots/:id", operation(deleteAgbots)) ({
    // logger.info("DELETE /agbots/"+params("id"))
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      TempDb.agbots.remove(id)
      TempDb.agbotsAgreements.remove(id)       // remove the agreements for this agbot
      AuthCache.agbots.remove(id)    // do this after removing from the real db, in case that fails
      status_=(HttpCode.DELETED)
      ApiResponse(ApiResponseType.OK, "agbot deleted from the exchange")
    } else {
      db.run(AgbotsTQ.getDeleteActions(id).transactionally.asTry).map({ xs =>
        logger.debug("DELETE /agbots/"+id+" result: "+xs.toString)
        xs match {
          case Success(v) => ;
            AuthCache.agbots.remove(id)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "agbot deleted")
          case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)    // not considered an error, because they wanted the resource gone and it is
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not deleted: "+t.toString)
          }
      })
    }
  })

  // =========== POST /agbots/{id}/heartbeat ===============================
  val postAgbotsHeartbeat =
    (apiOperation[ApiResponse]("postAgbotsHeartbeat")
      summary "Tells the exchange this agbot is still operating"
      notes "Lets the exchange know this agbot is still active. Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /agbots/{id}/heartbeat. */
  post("/agbots/:id/heartbeat", operation(postAgbotsHeartbeat)) ({
    // logger.info("POST /agbots/"+params("id")+"/heartbeat")
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      val agbot = TempDb.agbots.get(id)
      agbot match {
        case Some(agbot) => agbot.lastHeartbeat = ApiTime.nowUTC
          status_=(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "heartbeat successful")
        case None => status_=(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+id+"' not found")
      }
    } else {
      db.run(AgbotsTQ.getLastHeartbeat(id).update(ApiTime.nowUTC).asTry).map({ xs =>
        logger.debug("POST /agbots/"+id+"/heartbeat result: "+xs.toString)
        xs match {
          case Success(v) => try {        // there were no db errors, but determine if it actually found it or not
              val numUpdated = v.toString.toInt
              if (numUpdated > 0) {
                resp.setStatus(HttpCode.POST_OK)
                ApiResponse(ApiResponseType.OK, "agbot updated")
              } else {
                resp.setStatus(HttpCode.NOT_FOUND)
                ApiResponse(ApiResponseType.NOT_FOUND, "agbot '"+id+"' not found")
              }
            } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from agbot update: "+e) }    // the specific exception is NumberFormatException
          case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not updated: "+t.toString)
        }
      })
    }
  })

  /* ====== GET /agbots/{id}/agreements ================================ */
  val getAgbotAgreements =
    (apiOperation[GetAgbotAgreementsResponse]("getAgbotAgreements")
      summary("Returns all agreements this agbot is in")
      notes("""Returns all agreements in the exchange DB that this agbot is part of. Can be run by the owning user or the agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}/agreements. Normally called by the user to see all agreements of this agbot. */
  get("/agbots/:id/agreements", operation(getAgbotAgreements)) ({
    // logger.info("GET /agbots/"+params("id")+"/agreements")
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrAgbotId(BaseAccess.READ, id)
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      TempDb.agbotsAgreements.get(id) match {
        case Some(agbotValue) => GetAgbotAgreementsResponse(agbotValue.toMap, 0)
        case None => status_=(HttpCode.NOT_FOUND)
          GetAgbotAgreementsResponse(new HashMap[String,AgbotAgreement](), 0)
      }
    } else {
      db.run(AgbotAgreementsTQ.getAgreements(id).result).map({ list =>
        logger.debug("GET /agbots/"+id+"/agreements result size: "+list.size)
        logger.trace("GET /agbots/"+id+"/agreements result: "+list.toString)
        val agreements = new MutableHashMap[String, AgbotAgreement]
        if (list.size > 0) for (e <- list) { agreements.put(e.agrId, e.toAgbotAgreement) }
        else resp.setStatus(HttpCode.NOT_FOUND)
        GetAgbotAgreementsResponse(agreements.toMap, 0)
      })
    }
  })

  /* ====== GET /agbots/{id}/agreements/{agid} ================================ */
  val getOneAgbotAgreement =
    (apiOperation[GetAgbotAgreementsResponse]("getOneAgbotAgreement")
      summary("Returns an agreement for a agbot")
      notes("""Returns the agreement with the specified agid for the specified agbot id in the exchange DB. Can be run by the owning user or the agbot. **Because of a swagger bug this method can not be run via swagger.**

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}/agreements/{agid}. */
  get("/agbots/:id/agreements/:agid", operation(getOneAgbotAgreement)) ({
    // logger.info("GET /agbots/"+params("id")+"/agreements/"+params("agid"))
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")   // but do not have a hack/fix for the agid
    val agrId = params("agid")
    validateUserOrAgbotId(BaseAccess.READ, id)
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      TempDb.agbotsAgreements.get(id) match {
        case Some(agbotValue) => val resp = GetAgbotAgreementsResponse(agbotValue.toMap.filter(d => d._1 == agrId), 0)
          if (!resp.agreements.contains(agrId)) status_=(HttpCode.NOT_FOUND)
          resp
        case None => status_=(HttpCode.NOT_FOUND)
          GetAgbotAgreementsResponse(new HashMap[String,AgbotAgreement](), 0)
      }
    } else {
      val resp = response
      db.run(AgbotAgreementsTQ.getAgreement(id, agrId).result).map({ list =>
        logger.debug("GET /agbots/"+id+"/agreements/"+agrId+" result: "+list.toString)
        val agreements = new MutableHashMap[String, AgbotAgreement]
        if (list.size > 0) for (e <- list) { agreements.put(e.agrId, e.toAgbotAgreement) }
        else resp.setStatus(HttpCode.NOT_FOUND)
        GetAgbotAgreementsResponse(agreements.toMap, 0)
      })
    }
  })

  // =========== PUT /agbots/{id}/agreements/{agid} ===============================
  val putAgbotAgreement =
    (apiOperation[ApiResponse]("putAgbotAgreement")
      summary "Adds/updates an agreement of a agbot"
      notes """Adds a new agreement of a agbot to the exchange DB, or updates an existing agreement. This is called by the owning user or the
        agbot to give their information about the agreement. The **request body** structure:

```
{
  "workload": "sdr-arm.json",    // workload template name
  "state": "negotiating"    // current agreement state: negotiating, signed, finalized, etc.
}
```"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot wanting to add/update this agreement."), paramType = ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutAgbotAgreementRequest],
          Option[String]("Agreement object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putAgbotAgreement2 = (apiOperation[PutAgbotAgreementRequest]("putAgreement2") summary("a") notes("a"))  // for some bizarre reason, the PutAgreementsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /agbots/{id}/agreements/{agid}. Normally called by agbot to add/update itself. */
  put("/agbots/:id/agreements/:agid", operation(putAgbotAgreement)) ({
    // logger.info("PUT /agbots/"+params("id")+"/agreements/"+params("agid"))
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val agrId = params("agid")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    val agreement = try { parse(request.body).extract[PutAgbotAgreementRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
    val createAgreement = (TempDb.agbotsAgreements.contains(id) && !TempDb.agbotsAgreements.get(id).get.contains(agrId))
      if (createAgreement && TempDb.agbotsAgreements.get(id).get.size >= ExchConfig.getInt("api.limits.maxAgreements")) {    // make sure they are not trying to overrun the exchange svr by creating a ton of agbot agreements
        status_=(HttpCode.ACCESS_DENIED)
        ApiResponse(ApiResponseType.ACCESS_DENIED, "can not create more than "+ExchConfig.getInt("api.limits.maxAgreements")+ " agreements for agbot "+agrId)
      } else {
        agreement.copyToTempDb(id, agrId)
        status_=(HttpCode.PUT_OK)
        ApiResponse(ApiResponseType.OK, "agreement added to or updated in the exchange")
      }
    } else {
      db.run(agreement.toAgbotAgreementRow(id, agrId).upsert.asTry).map({ xs =>
        logger.debug("PUT /agbots/"+id+"/agreements/"+agrId+" result: "+xs.toString)
        xs match {
          case Success(v) => resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "agreement added or updated")
          case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agrId+"' for agbot '"+id+"' not inserted or updated: "+t.toString)
        }
      })
    }
  })

  // =========== DELETE /agbots/{id}/agreements/{agid} ===============================
  val deleteAgbotAgreement =
    (apiOperation[ApiResponse]("deleteAgbotAgreement")
      summary "Deletes an agreement of a agbot"
      notes "Deletes an agreement of a agbot from the exchange DB. Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agreement. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /agbots/{id}/agreements/{agid}. */
  delete("/agbots/:id/agreements/:agid", operation(deleteAgbotAgreement)) ({
    // logger.info("DELETE /agbots/"+params("id")+"/agreements/"+params("agid"))
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val agrId = params("agid")
    validateUserOrAgbotId(BaseAccess.WRITE, params("id"))
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      TempDb.agbotsAgreements.get(params("id")) match {
        case Some(agbotValue) => agbotValue.remove(agrId)    // remove does *not* throw an exception if the key does not exist
        case None => ;
      }
      status_=(HttpCode.DELETED)
      ApiResponse(ApiResponseType.OK, "agreement deleted from the exchange")
    } else {
      db.run(AgbotAgreementsTQ.getAgreement(id,agrId).delete.asTry).map({ xs =>
        logger.debug("DELETE /agbots/"+id+"/agreements/"+agrId+" result: "+xs.toString)
        xs match {
          case Success(v) => try {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.DELETED)
              ApiResponse(ApiResponseType.OK, "agbot agreement deleted")
            } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from agbot agreement delete: "+e) }    // the specific exception is NumberFormatException
          case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agrId+"' for agbot '"+id+"' not deleted: "+t.toString)
          }
      })
    }
  })

  // =========== POST /agbots/{id}/dataheartbeat ===============================
  val postAgbotsDataHeartbeat =
    (apiOperation[ApiResponse]("postAgbotsDataHeartbeat")
      summary "Not supported yet - Tells the exchange that data has been received for these agreements"
      notes "Lets the exchange know that data has just been received for this list of agreement IDs. This is normally run by a cloud data aggregation service that is registered as an agbot of the same exchange user account that owns the agbots that are contracting on behalf of a workload. Can be run by the owning user or any of the agbots owned by that user. The other agbot that negotiated this agreement id can run POST /agbots/{id}/isrecentdata check the dataLastReceived value of the agreement to determine if the agreement should be canceled (if data verification is enabled)."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[List[String]],
          Option[String]("List of agreement IDs that have received data very recently."),
          paramType = ParamType.Body)
        )
      )

  /** Handles POST /agbots/{id}/dataheartbeat. */
  post("/agbots/:id/dataheartbeat", operation(postAgbotsDataHeartbeat)) ({
    // logger.info("POST /agbots/"+params("id")+"/dataheartbeat")
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    val agrIds = try { parse(request.body).extract[List[String]] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val agreementIds = agrIds.toSet

    //TODO: implement persistence
    // Find the agreement ids in any of this user's agbots
    val owner = TempDb.agbots.get(id) match {       // 1st find owner (user)
      case Some(agbot) => agbot.owner
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+id+"' not found"))
    }
    val agbotIds = TempDb.agbots.toMap.filter(a => a._2.owner == owner).keys.toSet      // all the agbots owned by this user
    val agbotsAgreements = TempDb.agbotsAgreements.toMap.filter(a => agbotIds.contains(a._1) && a._2.keys.toSet.intersect(agreementIds).size > 0)    // agbotsAgreements is hash of hash: 1st key is agbot id, 2nd key is agreement id, value is agreement info
    // we still have the same hash of hash, but have reduced the top level to only agbot ids that contain these agreement ids
    if (agbotsAgreements.size == 0) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agreement IDs not found"))
    // println(agbotsAgreements)

    // Now update the dataLastReceived value in all of these agreement id objects
    for ((id,agrMap) <- agbotsAgreements) {
      for ((agid, agr) <- agrMap; if agreementIds.contains(agid)) {
        agrMap.put(agid, AgbotAgreement(agr.workload, agr.state, agr.lastUpdated, ApiTime.nowUTC))   // copy everything from the original entry except dataLastReceived
      }
    }
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "data heartbeats successful")
  })

  // =========== POST /agbots/{id}/dataheartbeat/{agid} ===============================
  /** maybe delete this and only keep the 1 above?
  val postAgbotsDataHeartbeat2 =
    (apiOperation[ApiResponse]("postAgbotsDataHeartbeat2")
      summary "Deprecated - Tells the exchange data has been received for this agreement"
      notes "Lets the exchange know that data has just been received for this agreement. This is normally run by a cloud data aggregation service that is registered as an agbot of the same exchange user account that owns the agbots that are contracting on behalf of a workload. Can be run by the owning user or any of the agbots owned by that user. The other agbot that negotiated this agreement id can check the dataLastReceived of the agreement to determine if the agreement should be canceled (if data verification is enabled)."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to update the dataLastReceived field for."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
  */

  /** Handles POST /agbots/{id}/dataheartbeat/{agid}. */
  // post("/agbots/:id/dataheartbeat/:agid", operation(postAgbotsDataHeartbeat2)) ({
  post("/agbots/:id/dataheartbeat/:agid") ({
    // logger.info("POST /agbots/"+params("id")+"/dataheartbeat/"+params("agid"))
    val id = params("id")
    val agid = params("agid")
    validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    //TODO: implement persistence
    // Find the agreement id in any of this user's agbots
    val owner = TempDb.agbots.get(id) match {
      case Some(agbot) => agbot.owner
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+id+"' not found"))
    }
    val agbotIds = TempDb.agbots.toMap.filter(a => a._2.owner == owner).keys.toSet
    val agbotsAgreements = TempDb.agbotsAgreements.toMap.filter(a => agbotIds.contains(a._1) && a._2.contains(agid))    // agbotsAgreements is hash of hash: 1st key is agbot id, 2nd key is agreement id, value is agreement info
    // we still have the same hash of hash, but have reduced the top level to only agbot ids that contain this agreement id
    if (agbotsAgreements.size == 0) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agreement id '"+agid+"' not found"))
    // not sure under what circumstances more than 1 agbot would have an entry for this agreement, but handle it nonetheless
    // println(agbotsAgreements)
    for ((id,agrMap) <- agbotsAgreements) {
      agrMap.get(agid) match {
        case Some(agr) => agrMap.put(agid, AgbotAgreement(agr.workload, agr.state, agr.lastUpdated, ApiTime.nowUTC))   // copy everything from the original entry except dataLastReceived
        case None => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "did not find agreement id '"+agid+"' in agbot '"+id+"' as expected"))
      }
    }
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "data heartbeat successful")
  })

  // =========== POST /agbots/{id}/isrecentdata ===============================
  val postAgbotsIsRecentData =
    (apiOperation[List[PostAgbotsIsRecentDataElement]]("postAgbotsIsRecentData")
      summary "Not supported yet - Returns whether each agreement has received data or not"
      notes "Queries the exchange to find out if each of the specified agreement IDs has had POST /agbots/{id}/dataheartbeat run on it recently (within secondsStale ago). This is normally run by agbots that are contracting on behalf of this workload to decide whether the agreement should be canceled or not. Can be run by the owning user or any of the agbots owned by that user."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostAgbotsIsRecentDataRequest],
          Option[String]("List of agreement IDs that should be queried, and the time threshold to use."),
          paramType = ParamType.Body)
        )
      )
  val postAgbotsIsRecentData2 = (apiOperation[PostAgbotsIsRecentDataRequest]("postAgbotsIsRecentData2") summary("a") notes("a"))

  /** Handles POST /agbots/{id}/isrecentdata. */
  post("/agbots/:id/isrecentdata", operation(postAgbotsIsRecentData)) ({
    // logger.info("POST /agbots/"+params("id")+"/isrecentdata")
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    val req = try { parse(request.body).extract[PostAgbotsIsRecentDataRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val secondsStale = req.secondsStale
    val agreementIds = req.agreementIds.toSet

    //TODO: implement persistence
    // Find the agreement ids in any of this user's agbots
    val owner = TempDb.agbots.get(id) match {       // 1st find owner (user)
      case Some(agbot) => agbot.owner
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+id+"' not found"))
    }
    val agbotIds = TempDb.agbots.toMap.filter(a => a._2.owner == owner).keys.toSet      // all the agbots owned by this user
    val agbotsAgreements = TempDb.agbotsAgreements.toMap.filter(a => agbotIds.contains(a._1) && a._2.keys.toSet.intersect(agreementIds).size > 0)    // agbotsAgreements is hash of hash: 1st key is agbot id, 2nd key is agreement id, value is agreement info
    // we still have the same hash of hash, but have reduced the top level to only agbot ids that contain these agreement ids
    if (agbotsAgreements.size == 0) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agreement IDs not found"))
    // println(agbotsAgreements)

    // Now compare the dataLastReceived value with the current time
    var resp = List[PostAgbotsIsRecentDataElement]()
    for ((id,agrMap) <- agbotsAgreements) {
      for ((agid, agr) <- agrMap; if agreementIds.contains(agid)) {
        val recentData = !ApiTime.isSecondsStale(agr.dataLastReceived,secondsStale)
        resp = resp :+ PostAgbotsIsRecentDataElement(agid, recentData)
      }
    }
    status_=(HttpCode.POST_OK)
    resp
  })

  // =========== POST /agreements/confirm ===============================
  val postAgreementsConfirm =
    (apiOperation[ApiResponse]("postAgreementsConfirm")
      summary "Confirms if this agbot agreement is active"
      notes "Confirms whether or not this agreement id is valid, is owned by an agbot owned by this same username, and is a currently active agreement. Can only be run by an agbot or user."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username or agbot id. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password or token of the user/agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostAgreementsConfirmRequest],
          Option[String]("Agreement ID that should be confirmed."),
          paramType = ParamType.Body)
        )
      )
  val postAgreementsConfirm2 = (apiOperation[PostAgreementsConfirmRequest]("postAgreementsConfirm2") summary("a") notes("a"))

  /** Handles POST /agreements/confirm. */
  post("/agreements/confirm", operation(postAgreementsConfirm)) ({
    // logger.info("POST /agreements/confirm")
    val creds = validateUserOrAgbotId(BaseAccess.AGREEMENT_CONFIRM, "#")
    val req = try { parse(request.body).extract[PostAgreementsConfirmRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException

    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      // Get the owner of the agbots
      var owner = ""
      if (isAuthenticatedUser(creds)) owner = creds.id
      else {      // must be an agbot
        TempDb.agbots.get(creds.id) match {
          case Some(agbot) => owner = agbot.owner
          case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+creds.id+"' not found"))
        }
      }
      if (req.confirmTempDb(owner)) {
        status_=(HttpCode.POST_OK)
        ApiResponse(ApiResponseType.OK, "agreement active")
      } else {
        status_=(HttpCode.NOT_FOUND)
        ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
      }
    } else {    // persistence
      val owner = if (isAuthenticatedUser(creds)) creds.id else ""
      if (owner != "") {
        // the user invoked this rest method, so look for an agbot owned by this user with this agr id
        val agbotAgreementJoin = for {
          (agbot, agr) <- AgbotsTQ.rows joinLeft AgbotAgreementsTQ.rows on (_.id === _.agbotId)
          if agbot.owner === owner && agr.map(_.agrId) === req.agreementId
        } yield (agbot, agr)
        db.run(agbotAgreementJoin.result).map({ list =>
          logger.debug("POST /agreements/confirm of "+req.agreementId+" result: "+list.toString)
          // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.owner === owner && agr.agrId === req.agreementId
          if (list.size > 0 && !list.head._2.isEmpty && list.head._2.get.state != "") {
            resp.setStatus(HttpCode.POST_OK)
            ApiResponse(ApiResponseType.OK, "agreement active")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
          }
        })
      } else {
        // an agbot invoked this rest method, so look for the agbot with this id and for the agbot with this agr id, and see if they are owned by the same user
        val agbotAgreementJoin = for {
          (agbot, agr) <- AgbotsTQ.rows joinLeft AgbotAgreementsTQ.rows on (_.id === _.agbotId)
          if agbot.id === creds.id || agr.map(_.agrId) === req.agreementId
        } yield (agbot, agr)
        db.run(agbotAgreementJoin.result).map({ list =>
          logger.debug("POST /agreements/confirm of "+req.agreementId+" result: "+list.toString)
          if (list.size > 0) {
            // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.id === creds.id || agr.agrId === req.agreementId
            val agbot1 = list.find(r => r._1.id == creds.id).orNull
            val agbot2 = list.find(r => !r._2.isEmpty && r._2.get.agrId == req.agreementId).orNull
            if (agbot1 != null && agbot2 != null && agbot1._1.owner == agbot2._1.owner && agbot2._2.get.state != "") {
              resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, "agreement active")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
            }
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
          }
        })
      }
    }
  })

}