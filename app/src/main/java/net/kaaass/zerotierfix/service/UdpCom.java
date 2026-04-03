package net.kaaass.zerotierfix.service;

import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.PacketSender;
import com.zerotier.sdk.ResultCode;

import net.kaaass.zerotierfix.util.DebugLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * ZeroTier 与本地 UDP Socket 的桥接器。
 * <p>
 * 该类负责两件事：
 * 1) 将 ZeroTier Core 请求发送的数据包写入系统 UDP Socket；
 * 2) 持续读取系统 UDP Socket，并把收到的 UDP 数据交给 ZeroTier Core 处理。
 */
public class UdpCom implements PacketSender, Runnable {
    private static final String TAG = "UdpCom";
    private Node node;
    private final DatagramSocket svrSocket;
    private final ZeroTierOneService ztService;

    /**
     * 创建 UDP 通信桥接器。
     *
     * @param zeroTierOneService ZeroTier 服务实例，用于回调更新状态与触发关闭
     * @param datagramSocket     已绑定端口的 UDP Socket
     */
    UdpCom(ZeroTierOneService zeroTierOneService, DatagramSocket datagramSocket) {
        this.svrSocket = datagramSocket;
        this.ztService = zeroTierOneService;
    }

    /**
     * 设置当前对应的 ZeroTier 节点实例。
     *
     * @param node2 ZeroTier 节点对象
     */
    public void setNode(Node node2) {
        this.node = node2;
    }

    /**
     * 处理 ZeroTier Core 的发包请求。
     *
     * @param j                 当前网络标识（由 Core 透传，当前实现不使用）
     * @param inetSocketAddress 目标 UDP 地址
     * @param bArr              要发送的原始数据缓冲区
     * @param i                 TTL 参数（由 SDK 透传，当前实现不使用）
     * @return 0 表示发送成功，-1 表示发送失败
     */
    @Override
    public int onSendPacketRequested(long j, InetSocketAddress inetSocketAddress, byte[] bArr, int i) {
        if (this.svrSocket == null) {
            Log.e(TAG, "Attempted to send packet on a null socket");
            return -1;
        }
        if (bArr == null) {
            Log.e(TAG, "Attempted to send null packet buffer");
            return -1;
        }
        int sendLength = bArr.length;
        if (sendLength <= 0) {
            Log.w(TAG, "Drop empty UDP packet");
            return 0;
        }
        try {
            DatagramPacket datagramPacket = new DatagramPacket(bArr, sendLength, inetSocketAddress);
            DebugLog.d(TAG, "onSendPacketRequested: Sent " + datagramPacket.getLength()
                    + " bytes to " + inetSocketAddress.toString() + ", ttl=" + i);
            this.svrSocket.send(datagramPacket);
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send UDP packet", e);
            return -1;
        }
    }

    /**
     * 读取 UDP Socket 数据并转交给 ZeroTier Core。
     */
    public void run() {
        Log.d(TAG, "UDP Listen Thread Started.");
        try {
            long[] jArr = new long[1];
            byte[] bArr = new byte[16384];
            while (!Thread.currentThread().isInterrupted()) {
                jArr[0] = 0;
                DatagramPacket datagramPacket = new DatagramPacket(bArr, 16384);
                try {
                    this.svrSocket.receive(datagramPacket);
                    if (datagramPacket.getLength() > 0) {
                        if (this.node == null) {
                            Log.w(TAG, "Drop incoming packet because node is not ready");
                            continue;
                        }
                        byte[] bArr2 = new byte[datagramPacket.getLength()];
                        System.arraycopy(datagramPacket.getData(), 0, bArr2, 0, datagramPacket.getLength());
                        DebugLog.d(TAG, "Got " + datagramPacket.getLength() + " Bytes From: " + datagramPacket.getAddress().toString() + ":" + datagramPacket.getPort());
                        ResultCode processWirePacket = this.node.processWirePacket(System.currentTimeMillis(), -1, new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort()), bArr2, jArr);
                        if (processWirePacket != ResultCode.RESULT_OK) {
                            Log.e(TAG, "processWirePacket returned: " + processWirePacket.toString());
                            this.ztService.shutdown();
                        }
                        this.ztService.setNextBackgroundTaskDeadline(jArr[0]);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UDP listen loop failed", e);
        }
        Log.d(TAG, "UDP Listen Thread Ended.");
    }
}
