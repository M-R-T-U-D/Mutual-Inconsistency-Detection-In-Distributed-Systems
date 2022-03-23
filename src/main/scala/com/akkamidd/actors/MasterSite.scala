package com.akkamidd.actors
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.akkamidd.actors.Site.{BroadcastDone, Merged, SiteProtocol}
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

// the master actor who spawn the sites
object MasterSite {

  // MasterSiteProtocol - Defines the messages that dictates the protocol of the master site.
  sealed trait MasterSiteProtocol
  final case class Broadcast(
                              replyTo: ActorRef[MasterSiteProtocol],
                              msg: Site.SiteProtocol,
                              from: ActorRef[Site.SiteProtocol],
                              partitionSet: Set[ActorRef[SiteProtocol]]
                            ) extends MasterSiteProtocol

  final case class HandleBroadcastResp()
  final case class HandleFileUploadResp(msg: String)

  //find the partition that the part is in
  def splitPartition(
                      sitesPartitionedList: List[Set[ActorRef[SiteProtocol]]],
                      partToSplit:Set[ActorRef[SiteProtocol]]
                    ): List[Set[ActorRef[SiteProtocol]]] =
  {
    var newPartitionList:List[Set[ActorRef[SiteProtocol]]] = sitesPartitionedList
    for (set <- newPartitionList){
      if (partToSplit.subsetOf(set)) {
        // remove The old partition
        newPartitionList = newPartitionList.filter(!_.equals(set))
        // create new partition for the remaining part
        val setRemain = set -- partToSplit
        newPartitionList = newPartitionList :+ setRemain
        // create new partition for the partToSplit and append the new one to partition list
        newPartitionList = newPartitionList :+ partToSplit
        return newPartitionList
      }
    }
    throw new Exception("Not valid sub-partition in current DAG")
  }

  def mergePartition(
                      sitesPartitionedList: List[Set[ActorRef[SiteProtocol]]],
                      partToMerge:Set[ActorRef[SiteProtocol]]
                    ): List[Set[ActorRef[SiteProtocol]]] =
  {
    var setsToMerge: List[Set[ActorRef[SiteProtocol]]] = List()
    var newPartitionList:List[Set[ActorRef[SiteProtocol]]] = sitesPartitionedList

    if(partToMerge.isEmpty) {
      return newPartitionList
    }

    var numberOfSitesInFoundSets = 0
    for(set <- sitesPartitionedList) {
      if(set.subsetOf(partToMerge)) {
        // get the sets which need to be merged
        setsToMerge = setsToMerge :+ set
        // remove the set for the merge
        newPartitionList = newPartitionList.filter(!_.equals(set))

        numberOfSitesInFoundSets = numberOfSitesInFoundSets + set.size
      }
    }
    // numberOfSitesInFoundSets should be equal to the number of sites in the partToMerge set
    if(numberOfSitesInFoundSets != partToMerge.size) {
      throw new Exception("Not valid site set for merging: the partitions that need to be merge do not contain all the sites given in partToMerge")
    }

    newPartitionList :+ partToMerge
  }

  def printCurrentNetworkPartition(
                                    sitesPartitionedList: List[Set[ActorRef[SiteProtocol]]],
                                    context: ActorContext[MasterSiteProtocol]
                                  ): Unit =
  {
    val result = new StringBuilder()

    result.append("The network partition is: " )
    for(set <- sitesPartitionedList) {
      result.append("{")
      for(site <- set) {
        result.append(site.path.name)
        result.append(",")
      }
      // Remove last comma
      result.deleteCharAt(result.length() - 1)
      result.append("},")
    }
    // Remove last comma
    result.deleteCharAt(result.length()  - 1)
    context.log.info(result.toString())
  }

  // given a site "from", find a partition that the site is currently in
  def findPartitionSet(
                        from: ActorRef[SiteProtocol],
                        sitesPartitionedList: List[Set[ActorRef[SiteProtocol]]]
                      ): Set[ActorRef[SiteProtocol]] =
  {
    for (set <- sitesPartitionedList) {
      if (set.contains(from)) {
        return set
      }
    }
    // if the site is not found in partitionList , return a empty set
    Set[ActorRef[SiteProtocol]]()
  }

  def apply(): Behavior[MasterSiteProtocol] =
    Behaviors.setup { context =>
      // asking someone requires a timeout, if the timeout hits without response
      // the ask is failed with a TimeoutException
      implicit val timeout: Timeout = 2.seconds

      // create/spawn sites
      val siteA = context.spawn(Site(), "A")
      val siteB = context.spawn(Site(), "B")
      val siteC = context.spawn(Site(), "C")
      val siteD = context.spawn(Site(), "D")

      // test for splitting
      var init_sitePartitionList: List[Set[ActorRef[SiteProtocol]]] = List(Set(siteA,siteB,siteC,siteD))

      // upload files
      val time_a1 = System.currentTimeMillis().toString

      val partitionSet1 = findPartitionSet(siteA, init_sitePartitionList)
      context.ask(siteA, ref => Site.FileUpload(ref, time_a1, context.self, "test.txt", partitionSet1)) {
        case Success(BroadcastDone(msg)) => {
          context.log.info(s"$msg")
          HandleFileUploadResp(s"$msg")
        }
        case Failure(exception) => {
          context.log.info(s"$exception")
          HandleFileUploadResp(s"$exception")
        }
      }

//      siteA ! Site.FileUpload(time_a1, context.self, "test.txt", partitionSet1)

      // split into {A,B} {C,D}
      init_sitePartitionList = splitPartition(init_sitePartitionList, Set(siteA, siteB))
      context.log.info("Split 1, new PartitionList: {}", init_sitePartitionList)
      printCurrentNetworkPartition(init_sitePartitionList, context)

//      val partitionSet2 = findPartitionSet(siteA, init_sitePartitionList)
//
//      context.ask(siteA, ref => Site.FileUpdate(ref, ("A", time_a1), context.self, partitionSet2)) {
//        case Success(value) => context.log.info(s"$value")
//        case Failure(exception) => context.log.info(s"$exception")
//      }
//
//      context.ask(siteA, ref => Site.FileUpdate(ref, ("A", time_a1), context.self, partitionSet2)) {
//        case Success(value) => context.log.info(s"$value")
//        case Failure(exception) => context.log.info(s"$exception")
//      }
//
//      // merge into {A, B, C, D}
//      init_sitePartitionList = mergePartition(init_sitePartitionList, Set(siteA, siteB, siteC, siteD))
//      context.log.info("Merge 1, new PartitionList: {}",init_sitePartitionList)
//      context.log.info(init_sitePartitionList.toString())
//      printCurrentNetworkPartition(init_sitePartitionList, context)
//
//      val partitionSet3 = findPartitionSet(siteA, init_sitePartitionList)
//      siteA ! Merged(siteC, context.self, partitionSet3)
//
//      siteA ! Site.FileUpdate(("A", time_a1), context.self, partitionSet3)

      // merge {A} , {B} in {A} {B, C} {D} -> {A, B, C} {D}
      // siteA ! Merged(siteB, )
      //            siteB ! CheckInconsistency(fileListA)
      //                  1- Call ID for inconsistency checking (fileListA, fileListB) -> new fileList
      //                  2- Broadcast(ReplaceFileList(newFileList), context.self,


      Behaviors.receiveMessage {
        case Broadcast(replyTo, msg: SiteProtocol, from: ActorRef[SiteProtocol], partitionSet: Set[ActorRef[SiteProtocol]]) =>
          var requestId = 0
          val requestIdMax = partitionSet.size

          partitionSet.foreach { child =>
            if(!child.equals(from)) {
              child ! msg
              context.log.info(s"$requestId")
              requestId+=1
              if (requestId == requestIdMax) {
                replyTo ! HandleFileUploadResp(s"Done")
              }
//              context.ask(child, ref => msg) {
//                case Success(value) => {
//                  if (requestId == requestIdMax) {
//
//                  } else {
//                    requestId += 1
//
//                  }
//                }
//                case Failure(exception) =>
//              }
              context.log.info("from {} , send message to {}", from, child.toString)
            }
          }
          Behaviors.same

        case HandleFileUploadResp(msg: String) =>
          context.log.info(s"$msg")
          Behaviors.same

      }
    }

}
