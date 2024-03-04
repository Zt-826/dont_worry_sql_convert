package visitor;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLExprImpl;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.oracle.ast.expr.OracleOuterExpr;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectJoin;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectSubqueryTableSource;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectTableReference;
import com.alibaba.druid.util.FnvHash;
import com.alibaba.druid.util.StringUtils;
import entity.TableInfo;
import entity.TableRelation;

import java.util.*;
import java.util.stream.Collectors;

public class OracleToTiDBOutputVisitor extends OracleOutputVisitor {

    private final List<? extends Class<? extends SQLExprImpl>> commonClasses = Arrays.asList(SQLIntegerExpr.class, SQLCharExpr.class, SQLIdentifierExpr.class, SQLVariantRefExpr.class);

    public OracleToTiDBOutputVisitor(Appendable appender, boolean printPostSemi) {
        super(appender, printPostSemi);
    }

    public OracleToTiDBOutputVisitor(Appendable appender) {
        super(appender);
    }

    public boolean visit(OracleSelectQueryBlock x) {
        boolean parentIsSelectStatment = false;
        {
            if (x.getParent() instanceof SQLSelect) {
                SQLSelect select = (SQLSelect) x.getParent();
                if (select.getParent() instanceof SQLSelectStatement || select.getParent() instanceof SQLSubqueryTableSource) {
                    parentIsSelectStatment = true;
                }
            }
        }

        if (!parentIsSelectStatment) {
            return super.visit(x);
        }

        if (x.getWhere() instanceof SQLBinaryOpExpr //
                && x.getFrom() instanceof SQLSubqueryTableSource //
        ) {
            int rownum;
            String ident;
            SQLBinaryOpExpr where = (SQLBinaryOpExpr) x.getWhere();
            if (where.getRight() instanceof SQLIntegerExpr && where.getLeft() instanceof SQLIdentifierExpr) {
                rownum = ((SQLIntegerExpr) where.getRight()).getNumber().intValue();
                ident = ((SQLIdentifierExpr) where.getLeft()).getName();
            } else {
                return super.visit(x);
            }

            SQLSelect select = ((SQLSubqueryTableSource) x.getFrom()).getSelect();
            SQLSelectQueryBlock queryBlock = null;
            SQLSelect subSelect = null;
            SQLBinaryOpExpr subWhere = null;
            boolean isSubQueryRowNumMapping = false;

            if (select.getQuery() instanceof SQLSelectQueryBlock) {
                queryBlock = (SQLSelectQueryBlock) select.getQuery();
                if (queryBlock.getWhere() instanceof SQLBinaryOpExpr) {
                    subWhere = (SQLBinaryOpExpr) queryBlock.getWhere();
                }

                for (SQLSelectItem selectItem : queryBlock.getSelectList()) {
                    if (isRowNumber(selectItem.getExpr())) {
                        if (where.getLeft() instanceof SQLIdentifierExpr && ((SQLIdentifierExpr) where.getLeft()).getName().equals(selectItem.getAlias())) {
                            isSubQueryRowNumMapping = true;
                        }
                    }
                }

                SQLTableSource subTableSource = queryBlock.getFrom();
                if (subTableSource instanceof SQLSubqueryTableSource) {
                    subSelect = ((SQLSubqueryTableSource) subTableSource).getSelect();
                }
            }

            if ("ROWNUM".equalsIgnoreCase(ident)) {
                SQLBinaryOperator op = where.getOperator();
                Integer limit = null;
                if (op == SQLBinaryOperator.LessThanOrEqual) {
                    limit = rownum;
                } else if (op == SQLBinaryOperator.LessThan) {
                    limit = rownum - 1;
                }

                if (limit != null) {
                    select.accept(this);
                    println();
                    print0(ucase ? "LIMIT " : "limit ");
                    print(limit);
                    return false;
                }
            } else if (isSubQueryRowNumMapping) {
                SQLBinaryOperator op = where.getOperator();
                SQLBinaryOperator subOp = subWhere.getOperator();

                if (isRowNumber(subWhere.getLeft()) //
                        && subWhere.getRight() instanceof SQLIntegerExpr) {
                    int subRownum = ((SQLIntegerExpr) subWhere.getRight()).getNumber().intValue();

                    Integer offset = null;
                    if (op == SQLBinaryOperator.GreaterThanOrEqual) {
                        offset = rownum + 1;
                    } else if (op == SQLBinaryOperator.GreaterThan) {
                        offset = rownum;
                    }

                    if (offset != null) {
                        Integer limit = null;
                        if (subOp == SQLBinaryOperator.LessThanOrEqual) {
                            limit = subRownum - offset;
                        } else if (subOp == SQLBinaryOperator.LessThan) {
                            limit = subRownum - 1 - offset;
                        }

                        if (limit != null) {
                            subSelect.accept(this);
                            println();
                            print0(ucase ? "LIMIT " : "limit ");
                            print(offset);
                            print0(", ");
                            print(limit);
                            return false;
                        }
                    }
                }
            }
        }
        return super.visit(x);
    }

    // 处理(+)
    public void preVisit(SQLObject x) {
        if (x instanceof OracleSelectQueryBlock) {
            // 处理Oracle中的(+)写法，即from是OracleSelectJoin的情况
            if (((OracleSelectQueryBlock) x).getFrom() instanceof OracleSelectJoin) {
                OracleSelectQueryBlock oracleSelectQueryBlock = (OracleSelectQueryBlock) x;
                SQLBinaryOpExpr where = (SQLBinaryOpExpr) oracleSelectQueryBlock.getWhere();
                if (where == null) {
                    // 说明是个普通的join语句，不做额外处理
                    return;
                }

                // 获取所有的 别名 和 表名 的映射关系
                Map<String, String> aliasNameMap = new HashMap<>();
                // 获取 "别名 表名" 和 SQLTableSource 的映射关系
                Map<String, SQLTableSource> tableSourceMap = new HashMap<>();
                SQLTableSource from = ((OracleSelectQueryBlock) x).getFrom();
                getTableSources(from, aliasNameMap, tableSourceMap);

                List<TableRelation> tableRelations = new ArrayList<>();
                // 递归获取from中所有的关联关系
                parseTableRelationsInFrom(from, aliasNameMap, tableRelations);
                // 递归获取where中所有的关联关系
                parseTableRelationsInWhere(where, aliasNameMap, tableRelations);
                if (tableRelations.isEmpty()) {
                    // 不做额外处理
                    return;
                }

                // 为了处理两表未直接关联的特殊情况，先重新构建from中表的顺序
                Collections.sort(tableRelations);
                List<SQLTableSource> sortedTableSources = new ArrayList<>();
                List<TableInfo> sortedTableInfos = new ArrayList<>();
                // 超过一个表才涉及重构
                if (tableSourceMap.size() > 1) {
                    // 根据排序后的tableRelations，对tableSources进行排序
                    Set<String> sortedTableNames = new HashSet<>();
                    for (TableRelation tableRelation : tableRelations) {
                        if (tableRelation.getCommonRelation() != null) {
                            continue;
                        }

                        String preName = tableRelation.getPreTableNameAndAlias();
                        String postName = tableRelation.getPostTableNameAndAlias();
                        if (!sortedTableNames.contains(preName)) {
                            sortedTableNames.add(preName);
                            sortedTableSources.add(tableSourceMap.get(preName));
                            sortedTableInfos.add(new TableInfo(tableRelation.getPreTable().getTableName(), tableRelation.getPreTable().getAlias()));
                        }
                        if (!sortedTableNames.contains(postName)) {
                            sortedTableNames.add(postName);
                            sortedTableSources.add(tableSourceMap.get(postName));
                            sortedTableInfos.add(new TableInfo(tableRelation.getPostTable().getTableName(), tableRelation.getPostTable().getAlias()));
                        }
                    }

                    // 重构from中表的顺序
                    OracleSelectJoin newFrom = new OracleSelectJoin();
                    OracleSelectJoin curr = newFrom;
                    for (int i = sortedTableSources.size() - 1; i > 0; i--) {
                        curr.setRight(sortedTableSources.get(i));
                        // 这里随便填一个，不影响最终结果
                        curr.setJoinType(SQLJoinTableSource.JoinType.COMMA);
                        curr.setLeft(new OracleSelectJoin());
                        curr = (OracleSelectJoin) curr.getLeft();
                    }
                    ((OracleSelectJoin) curr.getParent()).setLeft(sortedTableSources.get(0));

                    from = newFrom;
                }


                // 处理from
                Set<String> joinedTable = new HashSet<>();
                List<TableRelation> joinedTableRelations = new ArrayList<>();
                handleFrom(from, sortedTableInfos, tableRelations, joinedTable, joinedTableRelations);
                oracleSelectQueryBlock.setFrom(from);


                // 处理where
                oracleSelectQueryBlock.setWhere(null);
                tableRelations.removeAll(joinedTableRelations);
                if (tableRelations.isEmpty()) {
                    return;
                }

                List<SQLBinaryOpExpr> commonRelations = tableRelations.stream().filter(tableRelation -> tableRelation.getCommonRelation() != null).map(TableRelation::getCommonRelation).collect(Collectors.toList());

                if (commonRelations.size() == 1) {
                    oracleSelectQueryBlock.setWhere(commonRelations.get(0));
                } else if (!commonRelations.isEmpty()) {
                    SQLBinaryOpExpr curr = where;
                    for (int i = commonRelations.size() - 1; i > 0; i--) {
                        curr.setRight(commonRelations.get(i));
                        curr.setLeft(new SQLBinaryOpExpr());
                        curr.setOperator(SQLBinaryOperator.BooleanAnd);
                        curr = (SQLBinaryOpExpr) curr.getLeft();
                    }
                    ((SQLBinaryOpExpr) curr.getParent()).setLeft(commonRelations.get(0));
                    oracleSelectQueryBlock.setWhere(where);
                    where.setParent(oracleSelectQueryBlock);
                }
            }
        }
    }

    private void handleFrom(SQLTableSource sqlTableSource, List<TableInfo> sortedTableInfos, List<TableRelation> tableRelations, Set<String> joinedTable, List<TableRelation> joinedTableRelations) {
        if (sqlTableSource instanceof OracleSelectJoin) {
            OracleSelectJoin selectJoin = (OracleSelectJoin) sqlTableSource;
            SQLTableSource left = selectJoin.getLeft();
            SQLTableSource right = selectJoin.getRight();

            // 递归处理：左
            // 处理左表，左表为表的情况
            if (left instanceof OracleSelectTableReference) {
                String alias = left.getAlias();
                if (StringUtils.isEmpty(alias)) {
                    joinedTable.add(((OracleSelectTableReference) left).getTableName() + " ");
                } else {
                    joinedTable.add(((OracleSelectTableReference) left).getTableName() + " " + alias);
                }
            }

            // 处理左表，左表为子查询的情况
            if (left instanceof OracleSelectSubqueryTableSource) {
                joinedTable.add(getSubqueryTableName() + " " + left.getAlias());
                preVisit(((OracleSelectSubqueryTableSource) left).getSelect().getQuery());
            }

            // 处理左表，左表为join的情况
            if (left instanceof OracleSelectJoin) {
                handleFrom(left, sortedTableInfos, tableRelations, joinedTable, joinedTableRelations);
            }

            // 递归处理：右
            // 如果右表为表
            if (right instanceof OracleSelectTableReference) {
                String alias = right.getAlias();
                if (StringUtils.isEmpty(alias)) {
                    joinedTable.add(((OracleSelectTableReference) right).getTableName() + " ");
                } else {
                    joinedTable.add(((OracleSelectTableReference) right).getTableName() + " " + alias);
                }
            }
            // 如果右表为OracleSelectJoin
            if (right instanceof OracleSelectJoin) {
                handleFrom(right, sortedTableInfos, tableRelations, joinedTable, joinedTableRelations);
            }
            // 如果右表为子查询
            if (right instanceof OracleSelectSubqueryTableSource) {
                joinedTable.add(getSubqueryTableName() + " " + right.getAlias());
                preVisit(((OracleSelectSubqueryTableSource) right).getSelect().getQuery());
            }

            // 递归处理：中
            handleJoinCondition(selectJoin, sortedTableInfos, tableRelations, joinedTable, joinedTableRelations);
        }
    }

    private static String getSubqueryTableName() {
        // 加一个前缀，尽量将子查询置后
        return "~" + OracleSelectSubqueryTableSource.class.getSimpleName();
    }

    private void getTableSources(SQLTableSource sqlTableSource, Map<String, String> aliasNameMap, Map<String, SQLTableSource> tableSourceMap) {
        if (sqlTableSource instanceof OracleSelectTableReference) {
            String alias = sqlTableSource.getAlias();
            String tableName = ((OracleSelectTableReference) sqlTableSource).getTableName();
            if (!StringUtils.isEmpty(alias)) {
                aliasNameMap.put(alias, tableName);
                tableSourceMap.put(tableName + " " + alias, sqlTableSource);
            } else {
                tableSourceMap.put(tableName + " ", sqlTableSource);
            }
        }
        if (sqlTableSource instanceof OracleSelectSubqueryTableSource) {
            String alias = sqlTableSource.getAlias();
            aliasNameMap.put(alias, getSubqueryTableName());
            tableSourceMap.put(getSubqueryTableName() + " " + alias, sqlTableSource);
        }
        if (sqlTableSource instanceof OracleSelectJoin) {
            OracleSelectJoin selectJoin = (OracleSelectJoin) sqlTableSource;
            getTableSources(selectJoin.getLeft(), aliasNameMap, tableSourceMap);
            getTableSources(selectJoin.getRight(), aliasNameMap, tableSourceMap);
        }
    }

    private static void handleJoinCondition(OracleSelectJoin sqlTableSource, List<TableInfo> sortedTableInfos, List<TableRelation> tableRelations, Set<String> joinedTable, List<TableRelation> joinedTableRelations) {
        // 获取关联的关系
        List<SQLBinaryOpExpr> innerRelations = new ArrayList<>();
        List<SQLBinaryOpExpr> outerRelations = new ArrayList<>();

        List<String> sortedTableNames = sortedTableInfos.stream().map(TableInfo::getTableName).collect(Collectors.toList());
        List<String> sortedAliases = sortedTableInfos.stream().map(TableInfo::getAlias).collect(Collectors.toList());

        for (TableRelation tableRelation : tableRelations) {
            if (joinedTableRelations.contains(tableRelation)) {
                continue;
            }

            if (joinedTable.contains(tableRelation.getPreTableNameAndAlias()) && joinedTable.contains(tableRelation.getPostTableNameAndAlias())) {
                // 说明是外连接
                if (tableRelation.getOuterRelation() != null) {
                    if (tableRelation.getOuterRelationType() == null) {
                        SQLBinaryOpExpr outerRelation = tableRelation.getOuterRelation();
                        // 这里的(+)都在右侧，因为之前被转换了
                        String leftName = ((SQLIdentifierExpr) ((SQLPropertyExpr) outerRelation.getLeft()).getOwner()).getName();
                        String rightName = ((SQLIdentifierExpr) ((SQLPropertyExpr) ((OracleOuterExpr) outerRelation.getRight()).getExpr()).getOwner()).getName();

                        // 根据表名在sortedTableSources的顺序判断左右连接
                        int leftIndex = Math.max(sortedTableNames.indexOf(leftName), sortedAliases.indexOf(leftName));
                        int rightIndex = Math.max(sortedTableNames.indexOf(rightName), sortedAliases.indexOf(rightName));

                        if (leftIndex < rightIndex) {
                            sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN);
                        } else {
                            sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN);
                        }

                        // 消除(+)
                        outerRelation.setRight(((OracleOuterExpr) outerRelation.getRight()).getExpr());
                        outerRelations.add(outerRelation);
                    }

                    if (tableRelation.getOuterRelationType() == SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN) {
                        SQLBinaryOpExpr outerRelation = tableRelation.getOuterRelation();
                        // 注意这里不是从outRelation中取，而是直接取得leftTable和rightTable
                        String leftName = tableRelation.getLeftTable().getTableName();
                        String rightName = tableRelation.getRightTable().getTableName();

                        // 根据表名在sortedTableSources的顺序判断左右连接
                        int leftIndex = Math.max(sortedTableNames.indexOf(leftName), sortedAliases.indexOf(leftName));
                        int rightIndex = Math.max(sortedTableNames.indexOf(rightName), sortedAliases.indexOf(rightName));

                        if (leftIndex < rightIndex) {
                            sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN);
                        } else {
                            sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN);
                        }
                        outerRelations.add(outerRelation);
                    }

                    if (tableRelation.getOuterRelationType() == SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN) {
                        SQLBinaryOpExpr outerRelation = tableRelation.getOuterRelation();
                        // 注意这里不是从outRelation中取，而是直接取得leftTable和rightTable
                        String leftName = tableRelation.getLeftTable().getTableName();
                        String rightName = tableRelation.getRightTable().getTableName();

                        // 根据表名在sortedTableSources的顺序判断左右连接
                        int leftIndex = Math.max(sortedTableNames.indexOf(leftName), sortedAliases.indexOf(leftName));
                        int rightIndex = Math.max(sortedTableNames.indexOf(rightName), sortedAliases.indexOf(rightName));

                        if (leftIndex < rightIndex) {
                            sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN);
                        } else {
                            sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN);
                        }
                        outerRelations.add(outerRelation);
                    }
                }
                // 说明是内连接
                if (tableRelation.getInnerRelation() != null) {
                    sqlTableSource.setJoinType(SQLJoinTableSource.JoinType.JOIN);
                    innerRelations.add(tableRelation.getInnerRelation());
                }
                joinedTableRelations.add(tableRelation);
            }
        }

        // 重构连接节点
        // 如果是内连接
        if (sqlTableSource.getJoinType().equals(SQLJoinTableSource.JoinType.JOIN)) {
            // 拼接condition，这里只支持多表的and关联，不支持or或者其他关联
            if (innerRelations.size() == 1) {
                sqlTableSource.setCondition(innerRelations.get(0));
            } else if (!innerRelations.isEmpty()) {
                SQLBinaryOpExpr conditions = new SQLBinaryOpExpr();
                SQLBinaryOpExpr curr = conditions;
                for (int i = innerRelations.size() - 1; i > 0; i--) {
                    curr.setRight(innerRelations.get(i));
                    curr.setLeft(new SQLBinaryOpExpr());
                    curr.setOperator(SQLBinaryOperator.BooleanAnd);
                    curr = (SQLBinaryOpExpr) curr.getLeft();
                }
                ((SQLBinaryOpExpr) curr.getParent()).setLeft(innerRelations.get(0));
                sqlTableSource.setCondition(conditions);
            }
        } else {
            // 如果是外连接
            // 拼接condition，这里只支持多表的and关联，不支持or或者其他关联
            if (outerRelations.size() == 1) {
                sqlTableSource.setCondition(outerRelations.get(0));
            } else if (!outerRelations.isEmpty()) {
                SQLBinaryOpExpr conditions = new SQLBinaryOpExpr();
                SQLBinaryOpExpr curr = conditions;
                for (int i = outerRelations.size() - 1; i > 0; i--) {
                    curr.setRight(outerRelations.get(i));
                    curr.setLeft(new SQLBinaryOpExpr());
                    curr.setOperator(SQLBinaryOperator.BooleanAnd);
                    curr = (SQLBinaryOpExpr) curr.getLeft();
                }
                ((SQLBinaryOpExpr) curr.getParent()).setLeft(outerRelations.get(0));
                sqlTableSource.setCondition(conditions);
            }
        }
    }

    private void parseTableRelationsInFrom(SQLTableSource sqlTableSource, Map<String, String> aliasNameMap, List<TableRelation> tableRelations) {
        if (sqlTableSource == null) {
            return;
        }
        if (sqlTableSource instanceof OracleSelectSubqueryTableSource) {
            preVisit(((OracleSelectSubqueryTableSource) sqlTableSource).getSelect().getQuery());
        }
        if (sqlTableSource instanceof OracleSelectJoin) {
            OracleSelectJoin selectJoin = (OracleSelectJoin) sqlTableSource;
            SQLTableSource left = selectJoin.getLeft();
            SQLTableSource right = selectJoin.getRight();
            if (!(left instanceof OracleSelectTableReference)) {
                parseTableRelationsInFrom(left, aliasNameMap, tableRelations);
            }
            if (!(right instanceof OracleSelectTableReference)) {
                parseTableRelationsInFrom(right, aliasNameMap, tableRelations);
            }
            parseTableRelationsInWhere((SQLBinaryOpExpr) ((OracleSelectJoin) sqlTableSource).getCondition(), aliasNameMap, tableRelations);
        }
    }

    private void parseTableRelationsInWhere(SQLBinaryOpExpr sqlBinaryOpExpr, Map<String, String> aliasNameMap, List<TableRelation> tableRelations) {
        if (sqlBinaryOpExpr == null) {
            return;
        }
        SQLExpr left = sqlBinaryOpExpr.getLeft();
        SQLExpr right = sqlBinaryOpExpr.getRight();
        if (left instanceof SQLBinaryOpExpr) {
            parseTableRelationsInWhere((SQLBinaryOpExpr) left, aliasNameMap, tableRelations);
        }
        if (right instanceof SQLBinaryOpExpr) {
            parseTableRelationsInWhere((SQLBinaryOpExpr) right, aliasNameMap, tableRelations);
        }
        // 说明是右连接
        if (left instanceof OracleOuterExpr) {
            // 将(+)放到右边
            sqlBinaryOpExpr.setRight(left);
            sqlBinaryOpExpr.setLeft(right);
            parseTableRelationsInWhere(sqlBinaryOpExpr, aliasNameMap, tableRelations);
        }
        // 说明是左连接
        if (right instanceof OracleOuterExpr) {
            String leftName = ((SQLIdentifierExpr) ((SQLPropertyExpr) left).getOwner()).getName();
            String rightName = ((SQLIdentifierExpr) ((SQLPropertyExpr) (((OracleOuterExpr) right).getExpr())).getOwner()).getName();

            TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
            tableRelation.setOuterRelation(sqlBinaryOpExpr);
            tableRelations.add(tableRelation);
        }


        if (left instanceof SQLPropertyExpr && right instanceof SQLPropertyExpr) {
            SQLObject parent = sqlBinaryOpExpr.getParent();
            if (parent instanceof OracleSelectJoin) {
                OracleSelectJoin selectJoin = (OracleSelectJoin) parent;
                if (selectJoin.getJoinType().equals(SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN) || selectJoin.getJoinType().equals(SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN)) {
                    // 如果是左右连接
                    String leftName = ((SQLIdentifierExpr) ((SQLPropertyExpr) left).getOwner()).getName();
                    String rightName = ((SQLIdentifierExpr) ((SQLPropertyExpr) right).getOwner()).getName();

                    TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
                    tableRelation.setOuterRelation(sqlBinaryOpExpr);
                    tableRelation.setOuterRelationType(selectJoin.getJoinType());

                    SQLTableSource rightTable = ((OracleSelectJoin) parent).getRight();
                    String leftTableName = "";
                    String leftTableAlise = "";
                    String rightTableName = "";
                    String rightTableAlias = rightTable.getAlias();
                    if (rightTable instanceof OracleSelectTableReference) {
                        rightTableName = ((SQLIdentifierExpr) ((OracleSelectTableReference) rightTable).getExpr()).getName();
                    }
                    if (rightTable instanceof OracleSelectSubqueryTableSource) {
                        rightTableName = getSubqueryTableName();
                    }

                    String preTableName = tableRelation.getPreTable().getTableName();
                    String preAlias = tableRelation.getPreTable().getAlias();
                    String postTableName = tableRelation.getPostTable().getTableName();
                    String postAlias = tableRelation.getPostTable().getAlias();

                    if (rightTableName.equals(preTableName)) {
                        leftTableName = postTableName;
                        leftTableAlise = postAlias;
                    } else {
                        leftTableName = preTableName;
                        leftTableAlise = preAlias;
                    }

                    tableRelation.setLeftTable(new TableInfo(leftTableName, leftTableAlise));
                    tableRelation.setRightTable(new TableInfo(rightTableName, rightTableAlias));
                    tableRelations.add(tableRelation);
                    return;
                }
            }
            // 说明是内连接
            String leftName = ((SQLIdentifierExpr) ((SQLPropertyExpr) left).getOwner()).getName();
            String rightName = ((SQLIdentifierExpr) ((SQLPropertyExpr) right).getOwner()).getName();

            TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
            tableRelation.setInnerRelation(sqlBinaryOpExpr);
            tableRelations.add(tableRelation);
        }

        // 说明是普通条件，需要把条件保留在where中
        // 判断left和right的类型是不是属于SQLIntegerExpr、SQLCharExpr、SQLIdentifierExpr
        if (commonClasses.contains(left.getClass()) || commonClasses.contains(right.getClass())) {
            TableRelation tableRelation = new TableRelation();
            tableRelation.setCommonRelation(sqlBinaryOpExpr);
            tableRelations.add(tableRelation);
        }
    }

    private static TableRelation getTableRelation(Map<String, String> aliasNameMap, String leftName, String rightName) {
        String leftTableName = "";
        String leftAlias = "";
        String rightTableName = "";
        String rightAlias = "";

        if (aliasNameMap.containsKey(leftName)) {
            // 说明leftName是别名
            leftAlias = leftName;
            leftTableName = aliasNameMap.get(leftName);
        } else {
            // 说明leftName是表名，别名为空
            leftTableName = leftName;
        }

        if (aliasNameMap.containsKey(rightName)) {
            // 说明rightName是别名
            rightAlias = rightName;
            rightTableName = aliasNameMap.get(rightName);
        } else {
            // 说明rightName是表名，别名为空
            rightTableName = rightName;
        }

        return new TableRelation(leftTableName, leftAlias, rightTableName, rightAlias);
    }


    static boolean isRowNumber(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr) {
            return ((SQLIdentifierExpr) expr).hashCode64() == FnvHash.Constants.ROWNUM;
        }

        return false;
    }
}
