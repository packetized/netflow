package io.netflow.flows.cflow

import io.netflow.flows._
import io.wasted.util._

import io.netty.buffer._
import java.net.InetSocketAddress

import scala.language.postfixOps
import scala.util.{ Try, Failure, Success }

/**
 * NetFlow Version 10 Packet - FlowSet DataSet
 *
 * *-------*---------------*------------------------------------------------------*
 * | Bytes | Contents      | Description                                          |
 * *-------*---------------*------------------------------------------------------*
 * | 0-1   | version       | The version of NetFlow records exported 009          |
 * *-------*---------------*------------------------------------------------------*
 * | 2-3   | length        | Length of the current Flow                           |
 * *-------*---------------*------------------------------------------------------*
 * | 4-7   | exportTime    | Current time in milli since epoch when flow shipped  |
 * *-------*---------------*------------------------------------------------------*
 * | 8-11  | PackageSeq    | Sequence counter of total flows exported             |
 * *-------*---------------*------------------------------------------------------*
 * | 12-15 | Observ.Dom    | ObservationDomain ID                                 |
 * *-------*---------------*------------------------------------------------------*
 */
object NetFlowV10Packet extends Logger {
  private val headerSize = 16

  /**
   * Parse a v10 Flow Packet
   *
   * @param sender The sender's InetSocketAddress
   * @param buf Netty ByteBuf containing the UDP Packet
   * @param actor Actor responsible for this sender
   */
  def apply(sender: InetSocketAddress, buf: ByteBuf, actor: Wactor.Address): Try[NetFlowV10Packet] = Try[NetFlowV10Packet] {
    val version = buf.getInteger(0, 2).toInt
    if (version != 10) return Failure(new InvalidFlowVersionException(version))
    val packet = NetFlowV10Packet(sender, buf.readableBytes)

    val senderIP = sender.getAddress.getHostAddress
    val senderPort = sender.getPort
    if (packet.length < headerSize)
      return Failure(new IncompleteFlowPacketHeaderException)

    val length = buf.getInteger(2, 2).toInt
    if (packet.length < length) return Failure(new ShortFlowPacketException)

    //packet.count = buf.getInteger(2, 2).toInt
    packet.date = new org.joda.time.DateTime(buf.getInteger(4, 4) * 1000)
    packet.flowSequence = buf.getInteger(8, 4)
    packet.observationId = buf.getInteger(12, 4)

    var flowsetCounter = 0
    var packetOffset = headerSize

    // we use a mutable array here in order not to bash the garbage collector so badly
    // because whenever we append something to our vector, the old vectors need to get GC'd
    val flows = scala.collection.mutable.ArrayBuffer[Flow[_]]()
    while (packetOffset < packet.length) {
      val flowsetId = buf.getInteger(packetOffset, 2).toInt
      val flowsetLength = buf.getInteger(packetOffset + 2, 2).toInt
      if (flowsetLength == 0) return Failure(new IllegalFlowSetLengthException)
      if (packetOffset + flowsetLength > packet.length) return Failure(new ShortFlowPacketException)

      flowsetId match {
        case 0 | 2 => // template flowset - 0 NetFlow v10, 2 IPFIX
          var templateOffset = packetOffset + 4 // add the 4 byte flowset Header
          debug("Template FlowSet (" + flowsetId + ") from " + senderIP + "/" + senderPort)
          do {
            val fieldCount = buf.getUnsignedShort(templateOffset + 2)
            val templateSize = fieldCount * 4 + 4
            if (templateOffset + templateSize < packet.length) {
              val buffer = buf.slice(templateOffset, templateSize)
              NetFlowV10Template(sender, buffer, flowsetId) match {
                case Success(tmpl) =>
                  actor ! tmpl
                  flows += tmpl
                case Failure(e) => warn(e.toString)
              }
              flowsetCounter += 1
            }
            templateOffset += templateSize
          } while (templateOffset - packetOffset < flowsetLength)

        case 1 | 3 => // template flowset - 1 NetFlow v10, 3 IPFIX
          debug("OptionTemplate FlowSet (" + flowsetId + ") from " + senderIP + "/" + senderPort)
          var templateOffset = packetOffset + 4 // add the 4 byte flowset Header
          do {
            val fieldCount = buf.getInteger(templateOffset + 2, 2).toInt
            val scopeFieldCount = buf.getInteger(templateOffset + 4, 2).toInt
            val templateSize = fieldCount * 4
            if (templateOffset + templateSize < packet.length) {
              val buffer = buf.slice(templateOffset, templateSize)
              NetFlowV10Template(sender, buffer, flowsetId) match {
                case Success(tmpl) =>
                  actor ! tmpl
                  flows += tmpl
                case Failure(e) => warn(e.toString); e.printStackTrace
              }
              flowsetCounter += 1
            }
            templateOffset += templateSize
          } while (templateOffset - packetOffset < flowsetLength)

        case a: Int if a > 255 => // flowset - templateId == flowsetId
          NetFlowV10Template(sender, flowsetId) match {
            case Some(tmpl) =>
              val option = (tmpl.flowsetId == 1)
              var recordOffset = packetOffset + 4 // add the 4 byte flowset Header
              while (recordOffset - packetOffset + tmpl.length <= flowsetLength) {
                val buffer = buf.slice(recordOffset, tmpl.length)
                val flow =
                  if (option) NetFlowV10Option(sender, buffer, tmpl, packet.uptime)
                  else NetFlowV10Data(sender, buffer, tmpl, packet.uptime)

                flow match {
                  case Success(flow) => flows += flow
                  case Failure(e) => warn(e.toString)
                }
                flowsetCounter += 1
                recordOffset += tmpl.length
              }
            case _ =>
          }
        case a: Int => debug("Unexpected TemplateId (" + a + ")")
      }
      packetOffset += flowsetLength
    }
    packet.flows = flows.toVector
    packet
  }
}

case class NetFlowV10Packet(sender: InetSocketAddress, length: Int) extends FlowPacket {
  def version = "NetFlowV10 Packet"
  var flowSequence: Long = -1L
  var observationId: Long = -1L
}

object NetFlowV10Data extends Logger {
  import TemplateFields._
  val parseExtraFields = Config.getBool("netflow.extraFields", true)

  /**
   * Parse a Version 10 Flow
   *
   * @param sender The sender's InetSocketAddress
   * @param buf Netty ByteBuf containing the UDP Packet
   * @param template NetFlow Template for this Flow
   */
  def apply(sender: InetSocketAddress, buf: ByteBuf, template: NetFlowV10Template, uptime: Long): Try[NetFlowV10Data] = Try[NetFlowV10Data] {
    val flow = NetFlowV10Data(sender, buf.readableBytes(), template.id)
    flow.srcPort = (buf.getInteger(template, L4_SRC_PORT) getOrElse -1L).toInt
    flow.dstPort = (buf.getInteger(template, L4_DST_PORT) getOrElse -1L).toInt

    flow.srcAS = (buf.getInteger(template, SRC_AS) getOrElse -1L).toInt
    flow.dstAS = (buf.getInteger(template, DST_AS) getOrElse -1L).toInt
    flow.proto = (buf.getInteger(template, PROT) getOrElse 0L).toInt
    flow.tos = (buf.getInteger(template, SRC_TOS) getOrElse 0L).toInt

    // Add 0 seconds to router uptime if the flow has no data stamps
    flow.start = uptime + (buf.getInteger(template, FIRST_SWITCHED).getOrElse(0L).toInt / 1000)
    flow.stop = uptime + (buf.getInteger(template, LAST_SWITCHED).getOrElse(0L).toInt / 1000)

    flow.tcpflags = (buf.getInteger(template, TCP_FLAGS) getOrElse -1L).toInt

    flow.srcAddress = buf.getInetAddress(template, IPV4_SRC_ADDR, IPV6_SRC_ADDR)
    flow.dstAddress = buf.getInetAddress(template, IPV4_DST_ADDR, IPV6_DST_ADDR)
    flow.nextHop = buf.getInetAddress(template, IPV4_NEXT_HOP, IPV6_NEXT_HOP)

    flow.pkts = buf.getInteger(template, InPKTS, OutPKTS)
    flow.bytes = buf.getInteger(template, InBYTES, OutBYTES)

    if (parseExtraFields) flow.extraFields = template.getExtraFields(buf)
    flow
  }

}

case class NetFlowV10Data(val sender: InetSocketAddress, val length: Int, val template: Int) extends NetFlowData[NetFlowV10Data] {
  def version = "NetFlowV10Data " + template

  var extraFields = Map[String, Long]()
  override lazy val jsonExtra = ", " + extraFields.map(b => "\"" + b._1.toLowerCase + "\": " + b._2).mkString(", ")

  override lazy val stringExtra = "- Template %s".format(template)
}

object NetFlowV10Option extends Logger {

  /**
   * Parse a Version 10 Option Flow
   *
   * @param sender The sender's InetSocketAddress
   * @param buf Netty ByteBuf containing the UDP Packet
   * @param template NetFlow Template for this Flow
   */
  def apply(sender: InetSocketAddress, buf: ByteBuf, template: NetFlowV10Template, uptime: Long): Try[NetFlowV10Option] = Try[NetFlowV10Option] {
    val flow = NetFlowV10Option(sender, buf.readableBytes(), template.id)

    flow.extraFields = template.getExtraFields(buf)
    flow
  }

}

case class NetFlowV10Option(sender: InetSocketAddress, length: Int, template: Int) extends Flow[NetFlowV10Option] {
  def version = "NetFlowV10Option " + template

  var extraFields = Map[String, Long]()
  lazy val json = "{" + extraFields.map(b => "\"" + b._1 + "\": " + b._2).mkString(", ") + "}"

}

