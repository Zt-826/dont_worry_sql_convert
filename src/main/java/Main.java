import convertor.SQLConvertor;

import java.io.*;

public class Main {
    public static void readSqlStatementsFromFile(String filePath) throws IOException {
        StringBuilder currentSql = new StringBuilder();

        try (InputStream inputStream = Main.class.getResourceAsStream(filePath); BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    processSql(currentSql);
                } else {
                    currentSql.append(line).append("\n");
                }
            }

            // 处理最后可能没有跟随空行的SQL语句
            if (currentSql.length() > 0) {
                processSql(currentSql);
            }
        }
    }

    private static void processSql(StringBuilder sqlBuilder) {
        if (sqlBuilder.length() == 0) {
            return;
        }
        String tidbSql = SQLConvertor.translateOracleToTiDB(sqlBuilder.toString());
        System.out.println("原Oracle为:");
        System.out.println(sqlBuilder);
        System.out.println("转换为TiDB后：");
        System.out.println(tidbSql);
        System.out.println("\n\n");

        // 清空StringBuilder
        sqlBuilder.setLength(0);
    }

    public static void main(String[] args) throws IOException {
        readSqlStatementsFromFile("sql_cases.txt");
    }
}
