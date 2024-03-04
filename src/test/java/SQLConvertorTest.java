import static org.junit.jupiter.api.Assertions.*;

import convertor.SQLConvertor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SQLConvertorTest {
    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("YamlInputOutputDataProvider#provideInputOutputPairs")
    public void testInputOutput(String comment, String oracleSql, String tidbSql) {
        String output = SQLConvertor.translateOracleToTiDB(oracleSql);
        assertEquals(format(tidbSql), format(output));
        System.out.println("原Oracle为:");
        System.out.println(oracleSql);
        System.out.println("转换为TiDB后：");
        System.out.println(output);
    }

    private String format(String Sql) {
        return Sql.replaceAll("\\s+", " ").toLowerCase().trim();
    }
}