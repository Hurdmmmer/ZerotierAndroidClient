package net.kaaass.zerotierfix.service;

import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;

import net.kaaass.zerotierfix.util.NetworkInfoUtils;
import net.kaaass.zerotierfix.util.StringUtils;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 负责创建 IPv4/IPv6 组播扫描任务，基于 ScheduledExecutorService 定时执行。
 */
final class MulticastScannerThreadFactory {
    /**
     * 内核 TUN 设备名。
     */
    private static final String TUN_INTERFACE = "tun0";
    /**
     * 组播组扫描周期（毫秒）。
     */
    private static final long SCAN_INTERVAL_MS = 1000L;
    /**
     * 停止时等待执行器回收的超时时间（毫秒）。
     */
    private static final long SHUTDOWN_TIMEOUT_MS = 2000L;

    /**
     * 组播扫描任务句柄。
     */
    static final class ScannerHandle {
        private final String tag;
        private final String protocol;
        private final ScheduledExecutorService executor;
        private final Runnable task;
        private ScheduledFuture<?> future;

        private ScannerHandle(String tag, String protocol, ScheduledExecutorService executor, Runnable task) {
            this.tag = tag;
            this.protocol = protocol;
            this.executor = executor;
            this.task = task;
        }

        /**
         * 启动定时扫描（幂等）。
         */
        synchronized void start() {
            if (isRunning()) {
                return;
            }
            this.future = this.executor.scheduleWithFixedDelay(
                    this.task,
                    0L,
                    SCAN_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
            Log.d(this.tag, this.protocol + " Multicast Scanner Scheduled.");
        }

        /**
         * 停止定时扫描并回收执行器（幂等）。
         */
        synchronized void stop() {
            if (this.future != null) {
                this.future.cancel(true);
                this.future = null;
            }
            this.executor.shutdownNow();
            try {
                this.executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Log.d(this.tag, this.protocol + " Multicast Scanner Stopped.");
        }

        /**
         * @return 是否处于运行状态
         */
        synchronized boolean isRunning() {
            return this.future != null && !this.future.isCancelled() && !this.future.isDone();
        }
    }

    /**
     * 工具类不允许实例化。
     */
    private MulticastScannerThreadFactory() {
    }

    /**
     * 创建 IPv4 组播扫描句柄。
     *
     * @param nodeSupplier      提供当前 ZeroTier 节点实例
     * @param networkIdSupplier 提供当前网络 ID
     * @param tag               日志标签
     * @return 扫描句柄
     */
    static ScannerHandle createIpv4(Supplier<Node> nodeSupplier, LongSupplier networkIdSupplier, String tag) {
        return create(nodeSupplier, networkIdSupplier, tag, false);
    }

    /**
     * 创建 IPv6 组播扫描句柄。
     *
     * @param nodeSupplier      提供当前 ZeroTier 节点实例
     * @param networkIdSupplier 提供当前网络 ID
     * @param tag               日志标签
     * @return 扫描句柄
     */
    static ScannerHandle createIpv6(Supplier<Node> nodeSupplier, LongSupplier networkIdSupplier, String tag) {
        return create(nodeSupplier, networkIdSupplier, tag, true);
    }

    /**
     * 统一构建扫描句柄。
     *
     * @param nodeSupplier      提供当前 ZeroTier 节点实例
     * @param networkIdSupplier 提供当前网络 ID
     * @param tag               日志标签
     * @param ipv6              true 表示 IPv6，false 表示 IPv4
     * @return 扫描句柄
     */
    private static ScannerHandle create(
            Supplier<Node> nodeSupplier,
            LongSupplier networkIdSupplier,
            String tag,
            boolean ipv6
    ) {
        List<String> subscriptions = new ArrayList<>();
        String protocol = ipv6 ? "IPv6" : "IPv4";
        String threadName = ipv6 ? "V6 Multicast Scanner" : "V4 Multicast Scanner";
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(namedThreadFactory(threadName));
        Runnable task = () -> scanOnce(nodeSupplier, networkIdSupplier, tag, ipv6, subscriptions);
        return new ScannerHandle(tag, protocol, executor, task);
    }

    /**
     * 执行一次扫描：读取当前接口组播组并做差集，执行增量订阅/退订。
     *
     * @param nodeSupplier      提供当前 ZeroTier 节点实例
     * @param networkIdSupplier 提供当前网络 ID
     * @param tag               日志标签
     * @param ipv6              true 表示 IPv6，false 表示 IPv4
     * @param subscriptions     上一轮缓存的组播组列表
     */
    private static void scanOnce(
            Supplier<Node> nodeSupplier,
            LongSupplier networkIdSupplier,
            String tag,
            boolean ipv6,
            List<String> subscriptions
    ) {
        try {
            Node node = nodeSupplier.get();
            if (node == null) {
                return;
            }
            List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface(TUN_INTERFACE, ipv6);
            List<String> newGroups = new ArrayList<>(groups);
            newGroups.removeAll(subscriptions);
            for (String group : newGroups) {
                applySubscription(node, networkIdSupplier.getAsLong(), group, ipv6, true, tag);
            }
            List<String> removedGroups = new ArrayList<>(subscriptions);
            removedGroups.removeAll(groups);
            for (String group : removedGroups) {
                applySubscription(node, networkIdSupplier.getAsLong(), group, ipv6, false, tag);
            }
            subscriptions.clear();
            subscriptions.addAll(groups);
        } catch (Exception e) {
            Log.e(tag, "Multicast scan failed: " + e.getMessage(), e);
        }
    }

    /**
     * 对单个组播组执行订阅或退订。
     *
     * @param node      ZeroTier 节点实例
     * @param networkId 网络 ID
     * @param groupHex  组播地址的十六进制字符串
     * @param ipv6      true 表示 IPv6，false 表示 IPv4
     * @param subscribe true 表示订阅，false 表示退订
     * @param tag       日志标签
     */
    private static void applySubscription(
            Node node,
            long networkId,
            String groupHex,
            boolean ipv6,
            boolean subscribe,
            String tag
    ) {
        try {
            byte[] multicastAddress = StringUtils.hexStringToBytes(groupHex);
            if (!ipv6) {
                // Linux /proc 的 IPv4 组播地址字节序与 SDK 期望相反，需要翻转。
                reverseBytes(multicastAddress);
            }
            long multicastMac = TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(multicastAddress));
            ResultCode result = subscribe
                    ? node.multicastSubscribe(networkId, multicastMac)
                    : node.multicastUnsubscribe(networkId, multicastMac);
            if (result != ResultCode.RESULT_OK) {
                Log.e(tag, "Error when calling "
                        + (subscribe ? "multicastSubscribe" : "multicastUnsubscribe")
                        + ": " + result);
            }
        } catch (Exception e) {
            Log.e(tag, e.toString(), e);
        }
    }

    /**
     * 原地翻转字节数组。
     *
     * @param data 需要翻转的数组
     */
    private static void reverseBytes(byte[] data) {
        for (int i = 0; i < data.length / 2; i++) {
            byte tmp = data[i];
            data[i] = data[(data.length - i) - 1];
            data[(data.length - i) - 1] = tmp;
        }
    }

    /**
     * 创建命名线程工厂，便于日志和问题排查。
     *
     * @param threadName 线程名
     * @return 线程工厂
     */
    private static ThreadFactory namedThreadFactory(String threadName) {
        return runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
    }
}
