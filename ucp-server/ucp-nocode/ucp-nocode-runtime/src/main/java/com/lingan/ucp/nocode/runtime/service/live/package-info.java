/**
 * 业务记录变更登记与提交后交付：写点登记「哪个对象的哪条记录变了」，同一事务的变更在提交之后合并成一份交给监听者。 只负责登记与交付，不依赖 Web、WebSocket、Redis 与任何
 * Mapper；分发给订阅连接、跨进程转发由监听者（Web 模块）实现。
 */
package com.lingan.ucp.nocode.runtime.service.live;
