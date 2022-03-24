package com.akkamidd

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.ActorContext
import com.akkamidd.actors.MasterSite
import com.akkamidd.actors.MasterSite.{FileUpdate, FileUpdateConfirm, FileUpload, MasterSiteProtocol, Merge, SpawnSite}
import com.akkamidd.actors.Site.SiteProtocol
import org.slf4j.Logger

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object AkkaMain extends App {

  def callMerge(masterSystem: ActorSystem[MasterSiteProtocol], timeout: Long, partitionSet: Set[String]): Unit = {
    val newPartitionList = mergePartition(partitionList, partitionSet)
    masterSystem.log.info("Merge, new PartitionList: {}", newPartitionList)
    printCurrentNetworkPartition(newPartitionList, masterSystem.log)
    masterSystem ! Merge()

    Thread.sleep(timeout)
  }

  def callSplit(masterSystem: ActorSystem[MasterSiteProtocol], timeout: Long, partitionSet: Set[String]): Unit = {
    val newPartitionList = splitPartition(partitionSet, Set(siteA, siteB))
    masterSystem.log.info("Split, new PartitionList: {}", newPartitionList)
    printCurrentNetworkPartition(newPartitionList, masterSystem.log)

    Thread.sleep(timeout)
  }

  //find the partition that the part is in
  def splitPartition(
                      sitesPartitionedList: List[Set[String]],
                      partToSplit: Set[String]
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
                      sitesPartitionedList: ListBuffer[Set[String]],
                      partToMerge: Set[String]
                    ): Unit =
  {
    var setsToMerge: List[Set[ActorRef[SiteProtocol]]] = List()
    var newPartitionList:List[Set[String]] = sitesPartitionedList

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
                                    sitesPartitionedList: ListBuffer[Set[String]],
                                    logger: Logger
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

  // upload files
  val time_a1 = System.currentTimeMillis().toString

  val partitionList: mutable.ListBuffer[Set[String]] = ListBuffer()

  val masterSite: ActorSystem[MasterSiteProtocol] = ActorSystem(MasterSite(), "MasterSite")

  masterSite ! SpawnSite("A")
  partitionList += "A"

  masterSite ! SpawnSite("B")
  partitionList += "B"

  masterSite ! SpawnSite("C")
  partitionList += "C"

  masterSite ! SpawnSite("D")
  partitionList += "D"

  println(partitionList)

  Thread.sleep(500)

  masterSite ! FileUpload(time_a1)

  // split into {A,B} {C,D}
  callSplit(masterSite, 500, partitionList)

  masterSite ! FileUpdate(time_a1)

  Thread.sleep(500)

  //  merge into {A, B, C, D}
  callMerge(masterSite, 500, partitionList)

  masterSite ! FileUpdateConfirm(time_a1)

  masterSite ! FileUpdateConfirm(time_a1)
}

// merge {A} , {B} in {A} {B, C} {D} -> {A, B, C} {D}
// siteA ! Merged(siteB, )
//            siteB ! CheckInconsistency(fileListA)
//                  1- Call ID for inconsistency checking (fileListA, fileListB) -> new fileList
//                  2- Broadcast(ReplaceFileList(newFileList), context.self,