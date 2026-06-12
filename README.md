# Banking

Мини-банкинг на Java + SQL с простым HTML-сайтом.

## Структура

```text
Banking/
├─ pom.xml
├─ README.md
├─ .gitignore
├─ scr/
│  ├─ script/       Java-файлы
│  └─ bazadannih/   SQL-схема базы данных
├─ html/            HTML-страницы
└─ css/             стили сайта
```

## Запуск

```bash
mvn clean compile exec:java
```

После запуска открыть:

```text
http://127.0.0.1:8080/
```

## Что есть

- главная страница;
- личный кабинет по ID счета;
- счета, карты, переводы и транзакции;
- страница базы данных с таблицами;
- SQL-база H2.
