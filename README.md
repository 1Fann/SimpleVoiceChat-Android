# Simple Voice Chat Android arm64

Simple Voice Chat 2.6.27 的非官方 Android arm64 适配。适用于 Android 8.0 及以上版本，通过 FCL 等启动器运行 Minecraft Java 版。

从 [GitHub Release](https://github.com/1Fann/SimpleVoiceChat-Android/releases/tag/v2.6.27-android-r1) 下载对应的文件。

- 方块版使用 Fabric 1.21.1 的 JAR。
- 重铸版使用 Forge 1.12.2 的 JAR。

把对应文件放入游戏实例的 `mods` 目录并替换旧语音模组。授予启动器麦克风权限，保持 `config/voicechat/voicechat-client.properties` 中的 `use_natives=true`。

两版包含 Android arm64 的 Opus、RNNoise 降噪和 SpeexDSP 自动增益。已通过 JNI 接口校验和 Opus 编解码互通测试。FCL 真机录音和通话仍待验证。

## 构建

适配源码、构建脚本和测试位于 [tools/voicechat-android](tools/voicechat-android/README.md)。在本机构建时，请将参数指向本机 JDK、Android SDK/NDK 和 ASM。构建需要从 Modrinth 下载 [Fabric 原版](https://modrinth.com/plugin/simple-voice-chat/version/dyhq1XyO) 和 [Forge 原版](https://modrinth.com/plugin/simple-voice-chat/version/YCa5DHCu)。

原模组由 Max Henkel 开发。适配包中的上游内容及第三方原生库遵循各自的许可。详见 [Simple Voice Chat 上游项目](https://github.com/henkelmax/simple-voice-chat)。
