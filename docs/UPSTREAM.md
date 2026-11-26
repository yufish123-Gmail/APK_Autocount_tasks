# 上游来源

本项目基于用户授权迁入以下上游内容。

- 仓库为 [yufish123-Gmail/Mgame-auto-count-tasks](https://github.com/yufish123-Gmail/Mgame-auto-count-tasks)。
- 固定提交为 `d352788a8018635857e485f22c4d198f7e942a95`。
- `android/app/src/main/python/mgame_core.py` 来自上游 `coin_helper.py` 的任务与识别逻辑。
- `android/app/src/main/python/templates/` 中的 78 张 PNG 来自上游同名目录。
- `tests/test_upstream_rules.py` 来自上游规则测试，移除了依赖桌面 ADB 实现的测试，Android 控制层另有适配测试。
- [upstream-manifest.json](upstream-manifest.json) 记录原始内容校验值。导入脚本只读上游 Git 提交，向本仓库写入文件。

移植调整包括移除 Windows、ADB 和命令行入口，注入 Android 截图与中文 OCR、可取消计时、手势和页面恢复。上游任务引擎通过 `android_runtime.py` 与 Kotlin 原生无障碍服务衔接。

该上游快照未附独立 LICENSE 文件。此次复制由仓库所有者明确授权用于本项目，后续对外许可范围需由所有者确定。
