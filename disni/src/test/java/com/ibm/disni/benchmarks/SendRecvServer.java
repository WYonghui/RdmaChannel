/*
 * DiSNI: Direct Storage and Networking Interface
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.disni.benchmarks;

import com.ibm.disni.rdma.RdmaEndpoint;
import com.ibm.disni.rdma.RdmaEndpointFactory;
import com.ibm.disni.rdma.RdmaPassiveEndpointGroup;
import com.ibm.disni.rdma.RdmaServerEndpoint;
import com.ibm.disni.rdma.verbs.*;
import org.apache.commons.cli.ParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * DISNI Benchmark SendRecvClient 服务端程序
 * java -cp disni-1.6-jar-with-dependencies.jar:disni-1.6-tests.jar com.ibm.disni.benchmarks.SendRecvServer -a 10.10.0.25 -s 64 -k 1000000
 */
public class SendRecvServer implements RdmaEndpointFactory<SendRecvServer.SendRecvEndpoint> {
	private RdmaPassiveEndpointGroup<SendRecvEndpoint> group;
	private String host;
	private int port;
	private int size;
	private int loop;
	private int recvQueueSize;

	public SendRecvServer(String host, int port, int size, int loop, int recvQueueSize) throws IOException{
		this.group = new RdmaPassiveEndpointGroup<SendRecvServer.SendRecvEndpoint>(1, recvQueueSize, 1, recvQueueSize*2);
		this.group.init(this);
		this.host = host;
		this.port = port;
		this.size = size;
		this.loop = loop;
		this.recvQueueSize = recvQueueSize;
	}

	public SendRecvServer.SendRecvEndpoint createEndpoint(RdmaCmId id, boolean serverSide)
			throws IOException {
		return new SendRecvEndpoint(group, id, serverSide, size, recvQueueSize);
	}


	private void run() throws Exception {
		System.out.println("SendRecvServer, size " + size + ", loop " + loop + ", recvQueueSize " + recvQueueSize + ", port " + port);

		RdmaServerEndpoint<SendRecvServer.SendRecvEndpoint> serverEndpoint = group.createServerEndpoint();
		InetAddress ipAddress = InetAddress.getByName(host);
		InetSocketAddress address = new InetSocketAddress(ipAddress, port);
		serverEndpoint.bind(address, 10);
		SendRecvServer.SendRecvEndpoint endpoint = serverEndpoint.accept();
		System.out.println("SendRecvServer, client connected, address " + address.toString());

		int opCount = 0;
		while (opCount < loop){
			endpoint.pollRecvs();
			int remaining = loop - opCount;
			int actualRecvs = endpoint.installRecvs(remaining);
//			System.out.println("actualRecvs = " + actualRecvs);
			int actualSends = endpoint.replySends(actualRecvs);
//			System.out.println("actualSends = " + actualRecvs);
			opCount += actualSends;
//			System.out.println("opCount " + opCount);
		}

		endpoint.awaitRecvs();
		endpoint.awaitSends();

		//close everything
		endpoint.close();
		serverEndpoint.close();
		group.close();
	}


	public static void main(String[] args) throws Exception {
		SendRecvCmdLine cmdLine = new SendRecvCmdLine("SendRecvServer");
		try {
			cmdLine.parse(args);
		} catch (ParseException e) {
			cmdLine.printHelp();
			System.exit(-1);
		}

		SendRecvServer server = new SendRecvServer(cmdLine.getIp(), cmdLine.getPort(),
				cmdLine.getSize(), cmdLine.getLoop(), cmdLine.getQueueDepth());
		server.run();
	}

	public static class SendRecvEndpoint extends RdmaEndpoint {
		private int bufferSize;
		private int pipelineLength;
		private ByteBuffer[] recvBufs;
		private ByteBuffer[] sendBufs;
		private IbvMr[] recvMRs;
		private IbvMr[] sendMRs;
		private SVCPostRecv[] recvCall;
		private SVCPostSend[] sendCall;
		private IbvWC[] wcList;
		private SVCPollCq poll;
		private int recvIndex;
		private int sendIndex;
		private int sendBudget;
		private int recvBudget;
		private int sendPending;
		private int recvPending;

		protected SendRecvEndpoint(RdmaPassiveEndpointGroup<? extends RdmaEndpoint> group, RdmaCmId idPriv, boolean serverSide, int bufferSize, int recvQueueSize) throws IOException {
			super(group, idPriv, serverSide);
			this.bufferSize = bufferSize;
			this.pipelineLength = recvQueueSize;
			this.recvBufs = new ByteBuffer[pipelineLength];
			this.sendBufs = new ByteBuffer[pipelineLength];
			this.recvCall = new SVCPostRecv[pipelineLength];
			this.sendCall = new SVCPostSend[pipelineLength];
			this.recvMRs = new IbvMr[pipelineLength];
			this.sendMRs = new IbvMr[pipelineLength];
			this.recvIndex = 0;
			this.sendIndex = 0;
			this.sendBudget = pipelineLength;
			this.recvBudget = pipelineLength;
			this.sendPending = 0;
			this.recvPending = 0;
		}

		public int replySends(int res) throws IOException {
			int ready = Math.min(res, sendBudget);
			for (int i = 0; i < ready; i++){
				int index = sendIndex % pipelineLength;
				sendIndex++;
//				System.out.println("sending response, wrid " + sendCall[index].getWrMod(0).getWr_id());
				sendCall[index].execute();
				this.sendBudget--;
				this.sendPending++;
			}
			return ready;
		}

		public int installRecvs(int res) throws IOException {
			int ready = Math.min(res, recvBudget);
			for (int i = 0; i < ready; i++){
				int index = recvIndex % pipelineLength;
				recvIndex++;
//				System.out.println("installing recv, wrid " + recvCall[index].getWrMod(0).getWr_id());
				recvCall[index].execute();
				recvBudget--;
				recvPending++;
			}
			return ready;
		}

		private int pollRecvs() throws IOException {
			if (recvPending == 0){
				return 0;
			}

			int recvCount = 0;
			while (recvCount == 0){
				int res = 0;
				while (res == 0) {
					res = poll.execute().getPolls();
				}
				for (int i = 0; i < res; i++){
					if (wcList[i].getOpcode() == 128){
//						System.out.println("recv from wrid " + wcList[i].getWr_id());
						recvCount++;
						recvBudget++;
						recvPending--;
					} else {
						sendBudget++;
						sendPending--;
					}
				}
			}
			return recvCount;
		}

		private void awaitRecvs() throws IOException {
			while (recvPending > 0) {
				int res = poll.execute().getPolls();
				if (res > 0){
					for (int i = 0; i < res; i++){
						if (wcList[i].getOpcode() == 128){
//							System.out.println("recv from wrid " + wcList[i].getWr_id());
							recvBudget++;
							recvPending--;
						} else {
							sendBudget++;
							sendPending--;
						}
					}
				}
			}
		}

		private void awaitSends() throws IOException {
			while (sendPending > 0) {
				int res = poll.execute().getPolls();
				if (res > 0){
					for (int i = 0; i < res; i++){
						if (wcList[i].getOpcode() == 128){
//							System.out.println("recv from wrid " + wcList[i].getWr_id());
							recvBudget++;
							recvPending--;
						} else {
							sendBudget++;
							sendPending--;
						}
					}
				}
			}
		}

		@Override
		protected synchronized void init() throws IOException {
			super.init();

			IbvCQ cq = getCqProvider().getCQ();
			this.wcList = new IbvWC[getCqProvider().getCqSize()];
			for (int i = 0; i < wcList.length; i++){
				wcList[i] = new IbvWC();
			}
			this.poll = cq.poll(wcList, wcList.length);

			for(int i = 0; i < pipelineLength; i++){
				recvBufs[i] = ByteBuffer.allocateDirect(bufferSize);
				sendBufs[i] = ByteBuffer.allocateDirect(bufferSize);
				this.recvCall[i] = setupRecvTask(recvBufs[i], i);
				this.sendCall[i] = setupSendTask(sendBufs[i], i);
			}

			this.installRecvs(pipelineLength);
		}

		public synchronized void close() throws IOException, InterruptedException {
			super.close();
			for(int i = 0; i < pipelineLength; i++){
				deregisterMemory(recvMRs[i]);
				deregisterMemory(sendMRs[i]);
			}
		}

		private SVCPostSend setupSendTask(ByteBuffer sendBuf, int wrid) throws IOException {
			ArrayList<IbvSendWR> sendWRs = new ArrayList<IbvSendWR>(1);
			LinkedList<IbvSge> sgeList = new LinkedList<IbvSge>();

			IbvMr mr = registerMemory(sendBuf).execute().free().getMr();
			sendMRs[wrid] = mr;
			IbvSge sge = new IbvSge();
			sge.setAddr(mr.getAddr());
			sge.setLength(mr.getLength());
			int lkey = mr.getLkey();
			sge.setLkey(lkey);
			sgeList.add(sge);

			IbvSendWR sendWR = new IbvSendWR();
			sendWR.setSg_list(sgeList);
			sendWR.setWr_id(wrid);
			sendWRs.add(sendWR);
			sendWR.setSend_flags(IbvSendWR.IBV_SEND_SIGNALED);
			sendWR.setOpcode(IbvSendWR.IbvWrOcode.IBV_WR_SEND.ordinal());

			return postSend(sendWRs);
		}

		private SVCPostRecv setupRecvTask(ByteBuffer recvBuf, int wrid) throws IOException {
			ArrayList<IbvRecvWR> recvWRs = new ArrayList<IbvRecvWR>(1);
			LinkedList<IbvSge> sgeList = new LinkedList<IbvSge>();

			IbvMr mr = registerMemory(recvBuf).execute().free().getMr();
			recvMRs[wrid] = mr;
			IbvSge sge = new IbvSge();
			sge.setAddr(mr.getAddr());
			sge.setLength(mr.getLength());
			int lkey = mr.getLkey();
			sge.setLkey(lkey);
			sgeList.add(sge);

			IbvRecvWR recvWR = new IbvRecvWR();
			recvWR.setWr_id(wrid);
			recvWR.setSg_list(sgeList);
			recvWRs.add(recvWR);

			return postRecv(recvWRs);
		}
	}
}
