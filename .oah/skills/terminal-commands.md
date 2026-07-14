---
name: terminal-commands
description: Windows PowerShell 环境下执行终端命令的正确方法，避免常见的命令分隔符、多行脚本、JDK 路径等问题
---
# Windows PowerShell 终端命令执行指南

## 核心规则

### 1. 不要使用 `&&` 连接命令

Windows PowerShell 5.x **不支持** `&&` 作为命令分隔符，会报错：
```
The token '&&' is not a valid statement separator in this version
```

**正确做法**：使用 `;` 分号代替

```powershell
# 错误
cd C:\Workspaces\oah-ai && mvn clean compile

# 正确
cd C:\Workspaces\oah-ai; mvn clean compile
```

### 2. 多行脚本必须写入文件执行

直接在终端粘贴多行 PowerShell 脚本会导致解析错误（换行符被截断、语句顺序错乱）。

**正确做法**：将脚本写入 `.ps1` 文件，然后用 `-File` 参数执行

```powershell
# 1. 写入脚本文件（用代码编辑器或重定向）
# 2. 执行
powershell -ExecutionPolicy Bypass -File "C:\Workspaces\oah-ai\script.ps1"
```

### 3. Maven 编译必须显式指定 JDK 21

系统 PATH 中默认是 Java 17，但本项目需要 Java 21。每次执行 Maven 命令前必须设置环境变量：

```powershell
$env:JAVA_HOME = "C:\Packages\dragonwell-21.0.11.0.11+10-GA"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn clean compile
```

如果忘记设置，会报错：
```
Fatal error compiling: 无效的标记: 21
```

### 4. Maven 输出过长时重定向到文件

Maven 编译输出可能很长，终端缓冲区会截断。建议重定向到文件再按关键字过滤：

```powershell
mvn clean compile 2>&1 | Out-File -FilePath "build-result.txt" -Encoding utf8
Get-Content "build-result.txt" | Select-String "BUILD|SUCCESS|FAILURE|ERROR|Compiling" | Out-String
```

## 常用命令模板

### 编译项目

```powershell
$env:JAVA_HOME = "C:\Packages\dragonwell-21.0.11.0.11+10-GA"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; Set-Location "C:\Workspaces\oah-ai"; mvn clean compile 2>&1 | Out-File -FilePath "build-result.txt" -Encoding utf8; Write-Output "DONE"
```

### 启动应用

```powershell
$env:JAVA_HOME = "C:\Packages\dragonwell-21.0.11.0.11+10-GA"; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; Set-Location "C:\Workspaces\oah-ai"; mvn spring-boot:run
```

### 查看编译结果

```powershell
Get-Content "C:\Workspaces\oah-ai\build-result.txt" | Select-String "BUILD|SUCCESS|FAILURE|ERROR|Compiling|warning" | Out-String
```

### Git safe.directory 错误

Git 2.35.2+ 在 Windows 上会检查仓库目录所有权，如果报错：
```
fatal: detected dubious ownership in repository
```

执行：
```powershell
git config --global --add safe.directory "C:\Workspaces\oah-ai"
```

### PowerShell 调用 REST API

PowerShell 中 `curl` 是 `Invoke-WebRequest` 的别名，POST 请求建议用：

```powershell
# GET 请求
Invoke-RestMethod -Uri "http://localhost:9081/api/tools" -Method GET

# POST 请求
Invoke-RestMethod -Uri "http://localhost:9081/api/chat" -Method POST -ContentType "application/json" -Body '{"prompt":"你好","sessionId":null}'
```

## 常见错误对照表

| 错误信息 | 原因 | 解决方案 |
|---------|------|---------|
| `The token '&&' is not a valid statement separator` | PowerShell 5.x 不支持 `&&` | 改用 `;` 分号 |
| `Fatal error compiling: 无效的标记: 21` | JAVA_HOME 指向 Java 17 而非 21 | 显式设置 JAVA_HOME 为 dragonwell-21 |
| `Unexpected token` 多行脚本解析错误 | 终端不支持多行粘贴 | 写入 .ps1 文件后执行 |
| `detected dubious ownership in repository` | Git 所有权检查失败 | `git config --global --add safe.directory <路径>` |
| `Could not resolve dependencies` | 依赖模块未安装 | 使用 `mvn clean compile` 从根目录编译全部 |
| `ToolMetadata 找不到` | Spring AI 2.0 包路径变更 | import 路径用 `org.springframework.ai.tool.metadata.ToolMetadata` |
