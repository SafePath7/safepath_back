# SafePath Backend

[[한국어]](README.md) | [[日本語]](README.ja.md)

SafePath サービスのバックエンド API サーバーです。Spring Boot をベースに、Web API、セキュリティ、入力値検証、JPA、PostgreSQL 連携に必要な依存関係を含んでいます。

## 技術スタック

- Java 21
- Spring Boot 4.1.2
- Gradle Wrapper
- Spring Web MVC、Spring Security、Spring Validation
- Spring Data JPA
- PostgreSQL
- Lombok

## はじめに

### 必要な環境

- JDK 21
- PostgreSQL（データベース機能を使用する場合）

### 実行

プロジェクトのルートディレクトリで、次のコマンドを実行します。

```powershell
./gradlew.bat bootRun
```

macOS または Linux では、次のコマンドを使用します。

```bash
./gradlew bootRun
```

別途ポートを指定していない場合、アプリケーションはデフォルトで `http://localhost:8080` から起動します。

## テスト

```powershell
./gradlew.bat test
```

現在のテストでは、Spring アプリケーションコンテキストが正常に読み込まれることを確認します。

## ビルド

```powershell
./gradlew.bat build
```

ビルド成果物は `build/libs` ディレクトリに生成されます。

## 設定

基本設定ファイルは `src/main/resources/application.properties` です。

現在、データベース接続情報は含まれていません。JPA を使用する機能を追加する場合は、PostgreSQL の接続情報を環境変数または別プロファイルの設定で管理してください。設定例は次のとおりです。

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/safepath
spring.datasource.username=DB_USERNAME
spring.datasource.password=DB_PASSWORD
spring.jpa.hibernate.ddl-auto=update
```

機密情報はリポジトリにコミットしないよう注意してください。

## プロジェクト構成

```text
src/
├── main/
│   ├── java/com/safepath/backend/
│   │   └── BackendApplication.java   # Spring Boot のエントリポイント
│   └── resources/
│       └── application.properties    # アプリケーション設定
└── test/
    └── java/com/safepath/backend/    # テストコード
```

## 主要コマンド

| コマンド | 説明 |
| --- | --- |
| `./gradlew.bat bootRun` | ローカル開発サーバーの起動（Windows） |
| `./gradlew.bat test` | テストの実行 |
| `./gradlew.bat build` | テスト実行後にアプリケーションをビルド |
| `./gradlew.bat clean` | ビルド成果物を削除 |
