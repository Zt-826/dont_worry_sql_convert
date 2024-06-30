package convertor;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import visitor.OracleToTiDBOutputVisitor;

import java.util.List;

public class SQLConvertor extends SQLUtils {
    public static String translateOracleToTiDB(String sql) {
        List<SQLStatement> stmtList = toStatementList(sql, DbType.oracle);

        StringBuilder out = new StringBuilder();
        OracleToTiDBOutputVisitor visitor = new OracleToTiDBOutputVisitor(out, false);
        for (SQLStatement sqlStatement : stmtList) {
            sqlStatement.accept(visitor);
        }

        return visitor.getAppender().toString();
    }
}
