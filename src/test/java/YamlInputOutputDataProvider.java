import org.junit.jupiter.params.provider.Arguments;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class YamlInputOutputDataProvider {
    public static Stream<Arguments> provideInputOutputPairs() throws IOException {
        InputStream inputStream = YamlInputOutputDataProvider.class.getResourceAsStream("/sql_cases.yaml");

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
