package org.spiffy.http

import javax.servlet.AsyncContext

import scala.util.matching.Regex

import akka.actor.{Actor,ActorRef}

import org.spiffy.config.SpiffyConfig
import org.spiffy.Helpers._
import org.spiffy.sample.controllers._
import org.spiffy.{WorkStealingSupervisedDispatcherService => pool}
import org.spiffy.http.HookType._

/**
 * This is the main router class that is responsible for dispatching requests to
 * the various controllers available in the application using the Spiffy framework.
 * </p>
 *
 * Spiffy allows the router to be configured in the following ways:
 * <ul>
 *  <li>
 *  Inlined response: If the router encounters an inlined string it will not
 *  invoke any controllers and will simply complete the request and send back
 *  that string to the client. For example:
 *  <pre>
 *  <code>
 *  // index page, no code, just text
 *  """^/$""".r -> "Welcome to Spiffy!",
 *  </code>
 *  </pre>
 *  </li>
 *
 *  <li>
 *  Akka Actor: Spiffy encounters an Akka Actor it will contruct an appropriate set of
 *  parameters that will be messaged over asynchronously to the actor.
 *
 * @author Hisham Mardam-Bey <hisham.mardambey@gmail.com>
 */
class Router extends Actor {  
  /**
   * Map that holds all controllers by class and instance of that classs
   */
  var controllers = Map[Class[Actor], ActorRef]()

  /**
   * Receives messages from the Spiffy filter. Attempts tp route the request if a
   * route is available or returns a "not found" (http code 404) otherwise.
   *
   * TODO: make 404 configurable.
   */
  def receive = {
    // decide where to route this request
    case ReqResCtx(req, res, ctx) => {
      // strip away the application root to get the URI to act on
      val uri = req.getRequestURI.substring(SpiffyConfig().APPROOT_LENGTH)

      // attempt to route the request and return a 404 if not possible
      if (route(uri, req, res, ctx) == false) {
	// TODO: make this configurable
        notFound(req, res, ctx)
      }
    }

    // if we get an unknown message ignore it
    case ignore => {
      println("Internal server error (request ignored): " + ignore)
    }
  }

  /**
   * Attempts to route the request to the proper controller or other part of the
   * framework. Routing is done based on the request URL which is matches against a
   * regular expression. If a match is successful the request goes through and this
   * method returns true, false is returned otherwise signalling that the framework
   * needs to return a "page not found" error (http 404 status).
   */ 
  def route(uri: String, req: SpiffyRequestWrapper, res: SpiffyResponseWrapper, ctx: AsyncContext): Boolean = {
    // iterate over the available routes and try to find one that matches the requested URL
    SpiffyConfig().ROUTES foreach {
      case (regex: Regex, controller) => {
	val pattern = regex.pattern
	val matcher = pattern.matcher(uri)

	// does this route match?
	if (matcher.matches) {
          controller match {
	    // route maps to a string
            case s: String => {
	      // send back the string and terminate the request
	      // TODO: should this call any hooks?
              res.getWriter.write(s)
	      ctx.complete
              return true
            }

	    // route maps to an actor representing a controller, send it a message 
	    // so it can continue processing the request
            case c: ActorRef => {
	      // get the controller
              val ctrl = c

	      // make sure controller is valid
	      if (ctrl == None) return false

              val cnt = matcher.groupCount
              var params = new scala.collection.mutable.MutableList[Any]()

              for (i <- 1 to cnt) params += matcher.group(i)
	      	      	      
	      // lets check if we need to run any before hooks
	      try {
		val comp = companion[BeforeHooks](ctrl.getActorClass.getName)

		if (comp != None) {
		  // run the first hook
		  comp.before(0) ! HookMsg(0, comp.before, Spiffy(params.toList , ViewMsg("", Map.empty), req, res, ctx, ctrl), Before)
		}
	      } catch {
		case e:Exception => {
		  // message the controller with the parameters that it needs, request, response, and context		  
		  ctrl ! Spiffy(params.toList, ViewMsg("", Map.empty), req, res, ctx, ctrl)
		}
	      }

	      // done, tell the caller everything went ok
	      return true
	    }

	    // catch all case in case an incompatible message is found, this never happens
	    case ignore => {
	      println("unhandled controller: " + ignore)
	      return false
	    }
	  }

	  // config is bad, 404
	  return false
	}
      }
    }

    // could not find a route, 404
    return false
  } 
}

object Router {
  val actor = pool(classOf[Router], 100)
  def apply() = actor
}
