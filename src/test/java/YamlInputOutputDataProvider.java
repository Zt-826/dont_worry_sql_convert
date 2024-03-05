import org.junit.jupiter.params.provider.Arguments;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class YamlInputOutputDataProvider {
    public static Stream<Arguments> provideFunctionSqlPairs() {
        InputStream inputStream = YamlInputOutputDataProvider.class.getResourceAsStream("/function_sql_cases.yaml");
        return getArgumentsStream(inputStream);
    }

    public static Stream<Arguments> provideSyntaxSqlPairs() {
        InputStream inputStream = YamlInputOutputDataProvider.class.getResourceAsStream("/syntax_sql_cases.yaml");
        return getArgumentsStream(inputStream);
    }
    public static Stream<Arguments> provideComplexSqlPairs() {
        InputStream inputStream = YamlInputOutputDataProvider.class.getResourceAsStream("/complex_sql_cases.yaml");
        return getArgumentsStream(inputStream);
    }

    private static Stream<Arguments> getArgumentsStream(InputStream inputStream) {
        Yaml yaml = new Yaml();
        List<Arguments> argumentsList = new ArrayList<>();
        for (Object o : yaml.loadAll(inputStream)) {
            @SuppressWarnings("unchecked") ArrayList<LinkedHashMap<String, String>> sqlPairs = (ArrayList<LinkedHashMap<String, String>>) o;
            for (LinkedHashMap<String, String> sqlPair : sqlPairs) {
                argumentsList.add(arguments(sqlPair.get("comment"), sqlPair.get("oracle_sql"), sqlPair.get("tidb_sql")));
            }
        }
        return argumentsList.stream();
    }

}
