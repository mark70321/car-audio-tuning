# 給 Claude Code 的專案守則

開始任何工作前先讀 `PLAN.md`，它是本專案唯一的規劃依據。

## 關於使用者
- 使用者 Marco 不會自己寫程式，所有實作由你完成；用簡單的繁體中文說明他需要做的操作。
- 規劃與方向討論在另一個 Claude 對話進行，結論會寫回 `PLAN.md`。若你發現實作結果與 `PLAN.md` 不符，或做了新的設計決定，請同步更新 `PLAN.md` 並在回報中說明。

## 範圍
- 嚴格依 `PLAN.md` 第 9 節的階段順序，前一階段未完成不做下一階段，不自行擴大範圍。

## 倉庫是公開的（public）
- **絕對不要 commit**：App 簽署金鑰（.jks／.keystore）、金鑰密碼、Play Console 服務帳號 JSON、任何 API key 或 token。這些一律放在 `.gitignore` 列出的檔案或倉庫外，用 `local.properties`／環境變數讀取。
- commit 前檢查 `git status`，看到 keystore、key、secret、password 字樣的檔案就停下來問使用者。
- Play 授權驗證用的「公開金鑰」本來就公開，可以放在程式裡。

## 量測相關
- 量測前提醒使用者先關掉階段 0 測試 App 的 EQ（恢復原狀並強制停止）。
- 判斷收音來源時，不能只看重複性；要搭配兩支手機交叉比對（見 `PLAN.md` 1a）。

## 設備判斷
- 一律以 API level 判斷 Android 版本，不相信系統顯示的版本字串（安卓大屏常虛報）。
