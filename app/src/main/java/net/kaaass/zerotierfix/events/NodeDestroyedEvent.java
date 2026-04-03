package net.kaaass.zerotierfix.events;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NodeDestroyedEvent {
    /**
     * true 表示仅进入监听态（Service 保活）；false 表示完整停服/用户主动关闭。
     */
    private final boolean keepServiceAlive;
}
