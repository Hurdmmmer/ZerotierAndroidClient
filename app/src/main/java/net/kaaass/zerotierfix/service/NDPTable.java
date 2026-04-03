package net.kaaass.zerotierfix.service;

import android.util.Log;

import net.kaaass.zerotierfix.util.IPPacketUtils;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * IPv6 邻居发现（NDP）缓存表。
 * <p>
 * 该类用于维护 IPv6 地址与 MAC 地址映射，并定期清理超时项。
 * 为保证并发一致性，所有表访问统一使用同一把锁。
 */
public class NDPTable {
    public static final String TAG = "NDPTable";
    private static final long ENTRY_TIMEOUT = 120000;
    private final Object tableLock = new Object();
    private final HashMap<Long, NDPEntry> entriesMap = new HashMap<>();
    private final HashMap<InetAddress, Long> inetAddressToMacAddress = new HashMap<>();
    private final HashMap<InetAddress, NDPEntry> ipEntriesMap = new HashMap<>();
    private final HashMap<Long, InetAddress> macAddressToInetAddress = new HashMap<>();
    private final Thread timeoutThread = new Thread("NDP Timeout Thread") {
        /* class com.zerotier.one.service.NDPTable.AnonymousClass1 */

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    for (NDPEntry nDPEntry : snapshotEntries()) {
                        if (nDPEntry.getTime() + NDPTable.ENTRY_TIMEOUT < System.currentTimeMillis()) {
                            synchronized (NDPTable.this.tableLock) {
                                removeEntryNoLock(nDPEntry);
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(NDPTable.TAG, "NDP timeout thread interrupted", e);
                    break;
                } catch (Exception e) {
                    Log.e(NDPTable.TAG, "NDP timeout cleanup failed", e);
                }
            }
            Log.d(NDPTable.TAG, "NDP Timeout Thread Ended.");
        }
    };

    /**
     * 创建 NDP 表并启动后台清理线程。
     */
    public NDPTable() {
        this.timeoutThread.start();
    }

    /**
     * 停止后台清理线程。
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
     * 更新/写入 NDP 表项。
     *
     * @param inetAddress IPv6 地址
     * @param j           MAC 地址
     */
    public void setAddress(InetAddress inetAddress, long j) {
        synchronized (this.tableLock) {
            this.inetAddressToMacAddress.put(inetAddress, Long.valueOf(j));
            this.macAddressToInetAddress.put(Long.valueOf(j), inetAddress);
            NDPEntry nDPEntry = new NDPEntry(j, inetAddress);
            this.entriesMap.put(Long.valueOf(j), nDPEntry);
            this.ipEntriesMap.put(inetAddress, nDPEntry);
        }
    }

    /**
     * 判断指定 IP 是否存在 MAC 映射。
     *
     * @param inetAddress IPv6 地址
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
            return this.macAddressToInetAddress.containsKey(Long.valueOf(j));
        }
    }

    /**
     * 根据 IPv6 地址查询 MAC。
     *
     * @param inetAddress IPv6 地址
     * @return MAC 地址，若不存在返回 -1
     */
    public long getMacForAddress(InetAddress inetAddress) {
        synchronized (this.tableLock) {
            if (!this.inetAddressToMacAddress.containsKey(inetAddress)) {
                return -1;
            }
            long longValue = this.inetAddressToMacAddress.get(inetAddress).longValue();
            updateNDPEntryTime(longValue);
            return longValue;
        }
    }

    /**
     * 根据 MAC 地址查询 IPv6 地址。
     *
     * @param j MAC 地址
     * @return IPv6 地址，若不存在返回 null
     */
    public InetAddress getAddressForMac(long j) {
        synchronized (this.tableLock) {
            if (!this.macAddressToInetAddress.containsKey(Long.valueOf(j))) {
                return null;
            }
            InetAddress inetAddress = this.macAddressToInetAddress.get(Long.valueOf(j));
            updateNDPEntryTime(inetAddress);
            return inetAddress;
        }
    }

    /**
     * 按 IPv6 地址更新时间戳。
     *
     * @param inetAddress IPv6 地址
     */
    private void updateNDPEntryTime(InetAddress inetAddress) {
        synchronized (this.tableLock) {
            NDPEntry nDPEntry = this.ipEntriesMap.get(inetAddress);
            if (nDPEntry != null) {
                nDPEntry.updateTime();
            }
        }
    }

    /**
     * 按 MAC 地址更新时间戳。
     *
     * @param j MAC 地址
     */
    private void updateNDPEntryTime(long j) {
        synchronized (this.tableLock) {
            NDPEntry nDPEntry = this.entriesMap.get(Long.valueOf(j));
            if (nDPEntry != null) {
                nDPEntry.updateTime();
            }
        }
    }

    /**
     * 构造 IPv6 邻居请求（Neighbor Solicitation）报文。
     *
     * @param inetAddress  源 IPv6 地址
     * @param inetAddress2 目标 IPv6 地址
     * @param j            本机 MAC 地址
     * @return NS 报文字节数组
     */
    public byte[] getNeighborSolicitationPacket(InetAddress inetAddress, InetAddress inetAddress2, long j) {
        byte[] bArr = new byte[72];
        System.arraycopy(inetAddress.getAddress(), 0, bArr, 0, 16);
        System.arraycopy(inetAddress2.getAddress(), 0, bArr, 16, 16);
        System.arraycopy(ByteBuffer.allocate(4).putInt(32).array(), 0, bArr, 32, 4);
        bArr[39] = 58;
        bArr[40] = -121;
        System.arraycopy(inetAddress2.getAddress(), 0, bArr, 48, 16);
        byte[] array = ByteBuffer.allocate(8).putLong(j).array();
        bArr[64] = 1;
        bArr[65] = 1;
        System.arraycopy(array, 2, bArr, 66, 6);
        System.arraycopy(ByteBuffer.allocate(2).putShort((short) ((int) IPPacketUtils.calculateChecksum(bArr, 0, 0, 72))).array(), 0, bArr, 42, 2);
        for (int i = 0; i < 40; i++) {
            bArr[i] = 0;
        }
        bArr[0] = 96;
        System.arraycopy(ByteBuffer.allocate(2).putShort((short) 32).array(), 0, bArr, 4, 2);
        bArr[6] = 58;
        bArr[7] = -1;
        System.arraycopy(inetAddress.getAddress(), 0, bArr, 8, 16);
        System.arraycopy(inetAddress2.getAddress(), 0, bArr, 24, 16);
        return bArr;
    }

    /**
     * 在持有表锁前提下移除一个 NDP 表项。
     *
     * @param entry 目标表项
     */
    private void removeEntryNoLock(NDPEntry entry) {
        this.macAddressToInetAddress.remove(entry.getMac());
        this.inetAddressToMacAddress.remove(entry.getAddress());
        this.entriesMap.remove(entry.getMac());
        this.ipEntriesMap.remove(entry.getAddress());
    }

    /**
     * 获取 NDP 表项快照，供超时线程安全遍历。
     *
     * @return NDP 表项快照列表
     */
    private List<NDPEntry> snapshotEntries() {
        synchronized (this.tableLock) {
            return new ArrayList<>(this.entriesMap.values());
        }
    }
}
