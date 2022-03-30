package com.akkamidd.timestamp
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.akkamidd.timestamp.SiteTimestamp.{Merged, SiteProtocol}


// the master actor who spawn the sites
object MasterSiteTimestamp {

  // MasterSiteProtocol - Defines the messages that dictates the protocol of the master site.
  sealed trait TimestampProtocol

  final case class Broadcast(
                              msg: SiteTimestamp.SiteProtocol,
                              from: ActorRef[SiteTimestamp.SiteProtocol],
                              partitionSet: Set[ActorRef[SiteProtocol]]
                            ) extends TimestampProtocol
  final case class FileUploadMasterSite(
                                         to: String,
                                         fileName: String,
                                         timestamp: String,
                                         partitionList: List[Set[String]]
                                       ) extends TimestampProtocol
  final case class FileUpdateMasterSite(
                                         to: String,
                                         fileName: String,
                                         newTimestamp: String,
                                         partitionList: List[Set[String]]
                                       ) extends TimestampProtocol
  final case class Merge(
                          fromSiteMerge: String,
                          toSiteMerge: String,
                          partitionList: List[Set[String]]
                        ) extends TimestampProtocol
  final case class SpawnSite(siteName: String) extends TimestampProtocol

  def apply(debugMode: Boolean): Behavior[TimestampProtocol] = Behaviors.setup {
    context => masterSiteReceive(context, List(), debugMode)
  }

  def findSiteGivenName(
                         siteName: String,
                         children: List[ActorRef[SiteProtocol]]
                       ): Option[ActorRef[SiteProtocol]] =
  {
    for (child <- children) {
      if (child.path.name.equals(siteName)) {
        return Some(child)
      }
    }
    None
  }

  def getPartitionActorRefSet(
                               children: List[ActorRef[SiteProtocol]],
                               partitionSetString: Set[String]
                             ): Set[ActorRef[SiteProtocol]] =
  {
    partitionSetString.map(s => {
      findSiteGivenName(s, children).get
    })
  }

  // given a site "from", find a partition that the site is currently in
  def findPartitionSet(
                        fromSite: String,
                        sitesPartitionedList: List[Set[String]]
                      ): Set[String] =
  {
    for (set <- sitesPartitionedList) {
      if (set.contains(fromSite)) {
        return set
      }
    }
    // if the site is not found in partitionList , return a empty set
    Set[String]()
  }

  def masterSiteReceive(
                         context: ActorContext[TimestampProtocol],
                         children: List[ActorRef[SiteProtocol]],
                         debugMode: Boolean
                       )
  : Behaviors.Receive[TimestampProtocol] = Behaviors.receiveMessage {



    case Broadcast(msg: SiteProtocol, from: ActorRef[SiteProtocol], partitionSet: Set[ActorRef[SiteProtocol]]) =>
      partitionSet.foreach { child =>
        if(!child.equals(from)) {
          child ! msg
          if (debugMode) {
            context.log.info("from {} , send message to {}", from, child.toString)
          }
        }
      }
      masterSiteReceive(context, children, debugMode)



    case FileUploadMasterSite(siteThatUploads: String, timestamp: String, fileName: String, partitionList: List[Set[String]]) =>
      val site = findSiteGivenName(siteThatUploads, children).get

      val getPartitionSet = findPartitionSet(siteThatUploads, partitionList)
      val partitionSetRefs = getPartitionActorRefSet(children, getPartitionSet)

      site ! SiteTimestamp.FileUpload(fileName, timestamp, context.self, partitionSetRefs)

      masterSiteReceive(context, children, debugMode)



    case FileUpdateMasterSite(siteThatUpdates: String, fileName: String, newTimestamp: String, partitionList: List[Set[String]]) =>
      val site = findSiteGivenName(siteThatUpdates, children).get

      val getPartitionSet = findPartitionSet(siteThatUpdates, partitionList)
      val partitionSetRefs = getPartitionActorRefSet(children, getPartitionSet)

      site ! SiteTimestamp.FileUpdate(fileName, newTimestamp, context.self, partitionSetRefs)

      masterSiteReceive(context, children, debugMode)

    case Merge(fromSiteMerge, toSiteMerge, partitionList) =>
      val siteFrom = findSiteGivenName(fromSiteMerge, children).get
      val siteTo = findSiteGivenName(toSiteMerge, children).get

      val partitionSet = findPartitionSet(fromSiteMerge, partitionList)
      val partitionSetRefs = getPartitionActorRefSet(children, partitionSet)

      siteFrom ! Merged(siteTo, context.self, partitionSetRefs)

      masterSiteReceive(context, children, debugMode)

    // create/spawn sites
    case SpawnSite(siteName: String) =>
      val spawnedSite = context.spawn(SiteTimestamp(debugMode), siteName)
      val newChildren = spawnedSite +: children

      if (debugMode) {
        context.log.info(s"$newChildren")
      }

      masterSiteReceive(context, newChildren, debugMode)
  }

}
