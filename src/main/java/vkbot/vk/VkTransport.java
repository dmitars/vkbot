package vkbot.vk;

import java.io.IOException;
import java.util.Map;

/** Низкоуровневый вызов метода VK API. Вынесен в интерфейс, чтобы его можно было подменить (демо-режим, тесты). */
public interface VkTransport {
    /** Возвращает «сырое» тело ответа (JSON). */
    String call(String method, Map<String, String> params) throws IOException;
}
