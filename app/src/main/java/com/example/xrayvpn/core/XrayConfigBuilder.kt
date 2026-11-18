package com.example.xrayvpn.core

/**
 * Creates the Xray JSON config for the demo VLESS + REALITY endpoint.
 * Replace the sample credentials here when you use your own server.
 */
object XrayConfigBuilder {
    fun build(serverAddress: String): String {
        val safeServerAddress = serverAddress.replace("\\", "\\\\").replace("\"", "\\\"")

        return """
            {
              "log": { "loglevel": "warning" },
              "stats": {},
              "policy": {
                "system": {
                  "statsOutboundUplink": true,
                  "statsOutboundDownlink": true
                }
              },
              "inbounds": [
                {
                  "tag": "tun-in",
                  "protocol": "tun",
                  "settings": {
                    "mtu": 1280,
                    "userLevel": 8
                  }
                }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "address": "$safeServerAddress",
                    "port": 443,
                    "id": "fb759958-b17f-4da2-84ac-03796206eb12",
                    "encryption": "none",
                    "flow": "xtls-rprx-vision",
                    "level": 8
                  },
                  "streamSettings": {
                    "method": "raw",
                    "security": "reality",
                    "realitySettings": {
                      "show": false,
                      "fingerprint": "chrome",
                      "serverName": "play.google.com",
                      "password": "lN1dFBctfyxI_hivur2gHxdbWb4n_c5Rf047iCCPUkI",
                      "shortId": "3ccf670d31a26902",
                      "spiderX": "/"
                    }
                  }
                },
                {
                  "tag": "direct",
                  "protocol": "freedom",
                  "settings": {}
                },
                {
                  "tag": "block",
                  "protocol": "blackhole",
                  "settings": { "response": { "type": "http" } }
                }
              ],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  {
                    "type": "field",
                    "inboundTag": ["tun-in"],
                    "outboundTag": "proxy"
                  }
                ]
              }
            }
        """.trimIndent()
    }
}
