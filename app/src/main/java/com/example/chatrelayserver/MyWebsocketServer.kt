package com.example.chatrelayserver

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import java.time.Duration
import java.util.Collections
import java.util.LinkedHashSet

class MyWebsocketServer {

    // 接続(セッション)を管理するスレッドセーフなSet(リスト)
    // DefaultWebSocketSession は Ktor の WebSocket 接続そのものを表します
    // LinkedHashSetは重複のない接続順を保持したリスト
    // Collections.synchronizedSetはリストの追加削除を1つずつ実行するオブジェクト
    private val connections = Collections.synchronizedSet(LinkedHashSet<DefaultWebSocketSession>())

    // サーバー本体 (Nettyエンジン, ポート8080)
    // ※エコーサーバーの時は8000でしたが、今回は8080にしておきます (お好みで変更OK)
    private val netty = embeddedServer(Netty, port = 8080) {

        // JSONプラグインのインストール
        // 今は必要ないが、サーバー側でJSON読むなら要る
        install(ContentNegotiation) {
            json() // kotlinx.serialization を使う設定
        }

        // WebSocketプラグインのインストール
        install(WebSockets) {
            pingPeriod = Duration.ofMinutes(1) // 1分ごとに生存確認
            timeout = Duration.ofSeconds(15)   // 15秒応答がなければタイムアウト
            maxFrameSize = Long.MAX_VALUE      // フレームサイズ制限なし
            masking = false                    // マスキング無効
        }

        // ルーティング設定
        routing {
            webSocket("/") { // ルートパス ( ws://...:8080/ ) への接続

                // 1. 新しいクライアントが接続してきたら、管理リストに追加
                println("Connection established! Adding session: $this")
                connections.add(this) // 'this' が接続してきたセッションです

                try {
                    // 2. メッセージ受信ループ
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val receivedText = frame.readText()
                            println("Received: $receivedText. Broadcasting to ${connections.size} clients...")

                            // 3. 接続している全員にメッセージを中継 (ブロードキャスト)
                            connections.forEach { session ->
                                // 念のため、セッションがアクティブか確認
                                if (session.isActive) {
                                    // 送られてきたテキスト(JSON文字列)をそのまま送る
                                    session.send(Frame.Text(receivedText))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // クライアントが切断した場合 (アプリを閉じた、通信が途切れたなど)
                    println("Connection error: ${e.localizedMessage}")
                } finally {
                    // 4. 接続が切れたら (try-finally)、必ず管理リストから削除
                    println("Connection closed. Removing session: $this")
                    connections.remove(this)
                }
            }
        }
    }

    /** サーバーを起動する */
    fun start() {
        // wait=true で、サーバーが停止するまでこのスレッドをブロックする
        netty.start(wait = true)
    }

    /** サーバーを停止する */
    fun stop() {
        // 猶予期間0秒、タイムアウト5秒で優雅に停止
        netty.stop(0, 5000)
        connections.clear() // 念のためリストもクリア
    }
}