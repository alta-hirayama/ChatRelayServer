package com.example.chatrelayserver

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.net.Inet4Address
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var mWebSocketServer: MyWebsocketServer? = null

    // IPアドレスを保持するための状態(State)変数
    private val ipAddressState = mutableStateOf("Detecting IP...")

    // ネットワーク監視用のコールバック
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)
            Log.d("NetworkCallback", "LinkProperties changed: $linkProperties")
            updateIpAddress(linkProperties)
        }
        override fun onLost(network: Network) {
            super.onLost(network)
            // ネットワークが失われた場合、再度検出を試みる
            // (ただし、すぐ別のネットワークに切り替わるはずなので、ここではシンプルにIP未検出状態にする)
            ipAddressState.value = "Network Lost. Re-detecting..."
            Log.d("NetworkCallback", "Network lost")
            // すぐにアクティブなネットワークを確認し直す
            try {
                val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = manager.activeNetwork
                if (activeNetwork != null) {
                    val props = manager.getLinkProperties(activeNetwork)
                    if (props != null) {
                        updateIpAddress(props)
                    }
                } else {
                    ipAddressState.value = "No Active Network"
                }
            } catch (e: Exception) {
                Log.e("NetworkCallback", "Error re-checking IP on network lost", e)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. IPアドレスの監視を開始
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            // (a) 現在のIPアドレスを取得
            val currentNetwork = manager.activeNetwork
            val linkProperties = manager.getLinkProperties(currentNetwork)
            if (linkProperties != null) {
                updateIpAddress(linkProperties)
            } else {
                ipAddressState.value = "No active network"
                Log.d("NetworkCallback", "No active network found initially")
            }
            // (b) ネットワーク状態変化の監視を開始
            manager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: SecurityException) {
            Log.e("NetworkCallback", "Permission missing for initial check?", e)
            ipAddressState.value = "Permission Error?"
        } catch (e: Exception) {
            Log.e("NetworkCallback", "Error during initial IP check", e)
            ipAddressState.value = "Detection Error"
        }


        // 2. Jetpack ComposeでUIを構築
        setContent {
            // YourAppTheme { ... } のようなテーマで囲うのが一般的です
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("Chat Relay Server") }) // タイトル変更
                }
            ) { paddingValues ->
                // ipAddressStateの値が変わると、この画面も自動で再描画される
                ServerInfoScreen(
                    ipAddress = ipAddressState.value,
                    padding = paddingValues
                )
            }
        }

        // 3. バックグラウンドでサーバーを起動 (推奨される方法)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                // このブロックは、ActivityがCREATED状態以上のときだけ実行され、
                // STOPPED状態になると自動的にキャンセル（中断）されます。
                mWebSocketServer = MyWebsocketServer()
                thread { // Ktorのstart(wait=true)はスレッドをブロックするため、別スレッドで実行
                    try {
                        Log.i("MyWebsocketServer", "Server starting...")
                        mWebSocketServer?.start()
                        Log.i("MyWebsocketServer", "Server stopped.")
                    } catch (e: Exception) {
                        Log.e("MyWebsocketServer", "Server crashed", e)
                    }
                }
            }
            // (repeatOnLifecycleが終了したら、サーバーは自動的に停止処理に入る)
        }
    }

    /** LinkPropertiesからIPアドレスを抽出し、Stateを更新する */
    private fun updateIpAddress(linkProperties: LinkProperties) {
        val newIp = linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
            ?.address?.hostAddress

        if (newIp != null) {
            ipAddressState.value = newIp
            Log.d("NetworkCallback", "Found IP Address: $newIp")
        } else {
            ipAddressState.value = "Not Found (Wi-Fi or Hotspot only)"
            Log.d("NetworkCallback", "IP not found in LinkProperties")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ネットワーク監視を解除
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.unregisterNetworkCallback(networkCallback)

        // サーバーを終了
        thread {
            mWebSocketServer?.stop()
            mWebSocketServer = null
        }
    }
}

// 画面表示用のComposable関数
@Composable
fun ServerInfoScreen(ipAddress: String, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding) // Scaffoldからのpaddingを適用
            .padding(16.dp),  // コンテンツ自体のpadding
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "IPアドレス：",
                fontWeight = FontWeight.Bold
            )
            Text(text = ipAddress)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PORT：",
                fontWeight = FontWeight.Bold
            )
            // ポート番号を 8080 に変更
            Text(text = "8080")
        }
    }
}