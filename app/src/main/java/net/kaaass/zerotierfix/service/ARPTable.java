package net.kaaass.zerotierfix.service;

import android.util.Log;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * ARP 表缓存与 ARP 数据包处理器。
 * <p>
 * 该类维护 IP 与 MAC 的双向映射，并在后台线程中做超时清理。
 * 为避免多把锁交叉导致死锁，所有表项修改统一受同一把锁保护。
 */
public class ARPTable {
    public static final String TAG = "ARPTable";
    private static final long ENTRY_TIMEOUT = 120000;
    private static final int ARP_PACKET_LENGTH = 28;
    private static final int REPLY = 2;
    private static final int REQUEST = 1;
    private final Object tableLock = new Object();
    private final HashMap<Long, ARPEntry> entriesMap = new HashMap<>();
    private final HashMap<InetAddress, Long> inetAddressToMacAddress = new HashMap<>();
    private final HashMap<InetAddress, ARPEntry> ipEntriesMap = new HashMap<>();
    private final HashMap<Long, InetAddress> macAddressToInetAdddress = new HashMap<>();
    private final Thread timeoutThread = new Thread("ARP Timeout Thread") {
        /**
         * 周期性清理超时 ARP 表项。
         */
        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    for (ARPEntry arpEntry : snapshotEntries()) {
                        if (arpEntry.getTime() + ARPTable.ENTRY_TIMEOUT < System.currentTimeMillis()) {
                            Log.d(ARPTable.TAG, "Removing " + arpEntry.getAddress().toString() + " from ARP cache");
                            synchronized (ARPTable.this.tableLock) {
                                removeEntryNoLock(arpEntry);
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(ARPTable.TAG, "Tun/Tap Interrupted", e);
                    break;
                } catch (Exception e) {
                    Log.d(ARPTable.TAG, e.toString());
                }
            }
            Log.d(ARPTable.TAG, "ARP Timeout Thread Ended.");
        }
    };

    public ARPTable() {
        this.timeoutThread.start();
    }

    /**
     * 将 MAC（long）转换为 8 字节数组。
     *
     * @param j MAC 地址对应的 long 值
     * @return 8 字节数组
     */
    public static byte[] longToBytes(long j) {
        ByteBuffer allocate = ByteBuffer.allocate(8);
        allocate.putLong(j);
        return allocate.array();
    }

    /**
     * 停止后台超时线程。
     */
    public void stop() {
        try {
            this.timeoutThread.interrupt();
            this.timeoutThread.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 更新/写入 ARP 表项。
     *
     * @param inetAddress IP 地址
     * @param j           MAC 地址
     */
    public void setAddress(InetAddress inetAddress, long j) {
        synchronized (this.tableLock) {
            this.inetAddressToMacAddress.put(inetAddress, j);
            this.macAddressToInetAdddress.put(j, inetAddress);
            ARPEntry arpEntry = new ARPEntry(j, inetAddress);
            this.entriesMap.put(j, arpEntry);
            this.ipEntriesMap.put(inetAddress, arpEntry);
        }
    }

    /**
     * 按 MAC 更新时间戳。
     *
     * @param j MAC 地址
     */
    private void updateArpEntryTime(long j) {
        synchronized (this.tableLock) {
            ARPEntry arpEntry = this.entriesMap.get(j);
            if (arpEntry != null) {
                arpEntry.updateTime();
            }
        }
    }

    /**
     * 按 IP 更新时间戳。
     *
     * @param inetAddress IP 地址
     */
    private void updateArpEntryTime(InetAddress inetAddress) {
        synchronized (this.tableLock) {
            ARPEntry arpEntry = this.ipEntriesMap.get(inetAddress);
            if (arpEntry != null) {
                arpEntry.updateTime();
            }
        }
    }

    /**
     * 根据 IP 查询 MAC。
     *
     * @param inetAddress IP 地址
     * @return MAC 地址，若不存在返回 -1
     */
    public long getMacForAddress(InetAddress inetAddress) {
        synchronized (this.tableLock) {
            if (!this.inetAddressToMacAddress.containsKey(inetAddress)) {
                return -1;
            }
            Log.d(TAG, "Returning MAC for " + inetAddress.toString());
            Long longValue = this.inetAddressToMacAddress.get(inetAddress);
            if (longValue != null) {
                updateArpEntryTime(longValue);
                return longValue;
            }
        }
        return -1;
    }

    /**
     * 根据 MAC 查询 IP。
     *
     * @param j MAC 地址
     * @return IP 地址，若不存在返回 null
     */
    public InetAddress getAddressForMac(long j) {
        synchronized (this.tableLock) {
            if (!this.macAddressToInetAdddress.containsKey(j)) {
                return null;
            }
            InetAddress inetAddress = this.macAddressToInetAdddress.get(j);
            if (inetAddress != null) {
                updateArpEntryTime(inetAddress);
            }
            return inetAddress;
        }
    }

    /**
     * 判断指定 IP 是否存在 MAC 映射。
     *
     * @param inetAddress IP 地址
     * @return true 表示存在映射
     */
    public boolean hasMacForAddress(InetAddress inetAddress) {
        synchronized (this.tableLock) {
            return this.inetAddressToMacAddress.containsKey(inetAddress);
        }
    }

    /**
     * 判断指定 MAC 是否存在 IP 映射。
     *
     * @param j MAC 地址
     * @return true 表示存在映射
     */
    public boolean hasAddressForMac(long j) {
        synchronized (this.tableLock) {
            return this.macAddressToInetAdddress.containsKey(j);
        }
    }

    /**
     * 构造 ARP 请求包。
     *
     * @param j            源 MAC
     * @param inetAddress  源 IP
     * @param inetAddress2 目标 IP
     * @return ARP 请求字节数组
     */
    public byte[] getRequestPacket(long j, InetAddress inetAddress, InetAddress inetAddress2) {
        return getARPPacket(1, j, 0, inetAddress, inetAddress2);
    }

    /**
     * 构造 ARP 响应包。
     *
     * @param j            源 MAC
     * @param inetAddress  源 IP
     * @param j2           目标 MAC
     * @param inetAddress2 目标 IP
     * @return ARP 响应字节数组
     */
    public byte[] getReplyPacket(long j, InetAddress inetAddress, long j2, InetAddress inetAddress2) {
        return getARPPacket(2, j, j2, inetAddress, inetAddress2);
    }

    /**
     * 根据参数构造 ARP 包。
     *
     * @param i            ARP 操作码（1=请求，2=响应）
     * @param j            源 MAC
     * @param j2           目标 MAC
     * @param inetAddress  源 IP
     * @param inetAddress2 目标 IP
     * @return ARP 字节数组
     */
    public byte[] getARPPacket(int i, long j, long j2, InetAddress inetAddress, InetAddress inetAddress2) {
        byte[] bArr = new byte[28];
        bArr[0] = 0;
        bArr[1] = 1;
        bArr[2] = 8;
        bArr[3] = 0;
        bArr[4] = 6;
        bArr[5] = 4;
        bArr[6] = 0;
        bArr[7] = (byte) i;
        System.arraycopy(longToBytes(j), 2, bArr, 8, 6);
        System.arraycopy(inetAddress.getAddress(), 0, bArr, 14, 4);
        System.arraycopy(longToBytes(j2), 2, bArr, 18, 6);
        System.arraycopy(inetAddress2.getAddress(), 0, bArr, 24, 4);
        return bArr;
    }

    /**
     * 解析并处理 ARP 包，必要时返回 ARP 响应信息。
     *
     * @param packetData 原始 ARP 包数据
     * @return 需要响应时返回响应元数据；不需要响应或数据无效时返回 null
     */
    public ARPReplyData processARPPacket(byte[] packetData) {
        InetAddress srcAddress = null;
        InetAddress dstAddress = null;
        Log.d(TAG, "Processing ARP packet");
        if (packetData == null || packetData.length < ARP_PACKET_LENGTH) {
            Log.w(TAG, "Ignore invalid ARP packet: length=" + (packetData == null ? -1 : packetData.length));
            return null;
        }

        // 解析包内 IP、MAC 地址
        byte[] rawSrcMac = new byte[8];
        System.arraycopy(packetData, 8, rawSrcMac, 2, 6);
        byte[] rawSrcAddress = new byte[4];
        System.arraycopy(packetData, 14, rawSrcAddress, 0, 4);
        byte[] rawDstMac = new byte[8];
        System.arraycopy(packetData, 18, rawDstMac, 2, 6);
        byte[] rawDstAddress = new byte[4];
        System.arraycopy(packetData, 24, rawDstAddress, 0, 4);
        try {
            srcAddress = InetAddress.getByAddress(rawSrcAddress);
        } catch (Exception unused) {
        }
        try {
            dstAddress = InetAddress.getByAddress(rawDstAddress);
        } catch (Exception unused) {
        }
        long srcMac = ByteBuffer.wrap(rawSrcMac).getLong();
        long dstMac = ByteBuffer.wrap(rawDstMac).getLong();

        // 更新 ARP 表项
        if (srcMac != 0 && srcAddress != null) {
            setAddress(srcAddress, srcMac);
        }
        if (dstMac != 0 && dstAddress != null) {
            setAddress(dstAddress, dstMac);
        }

        // 处理响应行为
        var packetType = packetData[7];
        if (packetType == REQUEST) {
            // ARP 请求，返回应答数据
            Log.d(TAG, "Reply needed");
            return new ARPReplyData(srcMac, srcAddress);
        }
        return null;
    }

    /**
     * 在持有表锁的前提下删除指定 ARP 表项。
     *
     * @param entry 需要删除的表项
     */
    private void removeEntryNoLock(ARPEntry entry) {
        this.macAddressToInetAdddress.remove(entry.getMac());
        this.inetAddressToMacAddress.remove(entry.getAddress());
        this.entriesMap.remove(entry.getMac());
        this.ipEntriesMap.remove(entry.getAddress());
    }

    /**
     * 获取当前 ARP 表快照，用于超时线程安全遍历。
     *
     * @return ARP 表项快照
     */
    private List<ARPEntry> snapshotEntries() {
        synchronized (this.tableLock) {
            return new ArrayList<>(this.entriesMap.values());
        }
    }
}
