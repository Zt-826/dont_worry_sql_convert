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

    // 不侵入基本的访问行为，在preVisit中进行处理
    public void preVisit(SQLObject x) {
        if (x instanceof OracleSelectQueryBlock) {
            // 处理Oracle中的(+)写法，即from是OracleSelectJoin的情况
            if (((OracleSelectQueryBlock) x).getFrom() instanceof OracleSelectJoin) {
                OracleSelectQueryBlock oracleSelectQueryBlock = (OracleSelectQueryBlock) x;
                SQLExpr whereExpr = oracleSelectQueryBlock.getWhere();
                if (!(whereExpr instanceof SQLBinaryOpExpr)) {
                    // 说明是个普通的join语句，不做额外处理
                    return;
                }
                SQLBinaryOpExpr where = (SQLBinaryOpExpr) whereExpr;
                // 获取所有的 别名 和 表名 的映射关系
                Map<String, String> aliasNameMap = new HashMap<>();
                // 获取 "别名 表名" 和 SQLTableSource 的映射关系
                Map<String, SQLTableSource> tableSourceMap = new HashMap<>();
                SQLTableSource from = ((OracleSelectQueryBlock) x).getFrom();
                getTableSources(from, aliasNameMap, tableSourceMap);

                Set<TableRelation> tableRelations = new LinkedHashSet<>();
                // 递归获取from中所有的关联关系
                parseTableRelationsInFrom(from, aliasNameMap, tableRelations);
                // 递归获取where中所有的关联关系
                parseTableRelationsInWhere(where, aliasNameMap, tableRelations);
                if (tableRelations.isEmpty()) {
                    // 不做额外处理
                    return;
                }

                // 为了处理两表未直接关联的特殊情况，先重新构建from中表的顺序
                ArrayList<TableRelation> sortedTableRelations = new ArrayList<>(tableRelations);
                Collections.sort(sortedTableRelations);
                List<SQLTableSource> sortedTableSources = new ArrayList<>();
                List<TableInfo> sortedTableInfos = new ArrayList<>();
                // 超过一个表才涉及重构
                if (tableSourceMap.size() > 1) {
                    // 根据排序后的tableRelations，对tableSources进行排序
                    Set<String> sortedTableNames = new HashSet<>();
                    for (TableRelation tableRelation : sortedTableRelations) {
                        // 只处理内外连接
                        if (tableRelation.getInnerRelation() == null && tableRelation.getOuterRelation() == null) {
                            continue;
                        }

                        String preName = tableRelation.getPreTableNameAndAlias().toLowerCase();
                        String postName = tableRelation.getPostTableNameAndAlias().toLowerCase();
                        if (!sortedTableNames.contains(preName)) {
                            sortedTableNames.add(preName);
                            if (tableSourceMap.get(preName) != null) {
                                sortedTableSources.add(tableSourceMap.get(preName));
                                sortedTableInfos.add(new TableInfo(tableRelation.getPreTable().getTableName(), tableRelation.getPreTable().getAlias()));
                            }
                        }
                        if (!sortedTableNames.contains(postName)) {
                            sortedTableNames.add(postName);
                            if (tableSourceMap.get(postName) != null) {
                                sortedTableSources.add(tableSourceMap.get(postName));
                                sortedTableInfos.add(new TableInfo(tableRelation.getPostTable().getTableName(), tableRelation.getPostTable().getAlias()));
                            }
                        }
                    }

                    // 重构from中表的顺序
                    OracleSelectJoin newFrom = new OracleSelectJoin();
                    OracleSelectJoin curr = newFrom;
                    for (int i = sortedTableSources.size() - 1; i > 0; i--) {
                        SQLTableSource sqlTableSource = sortedTableSources.get(i);
                        if (sqlTableSource == null) {
                            continue;
                        }
                        curr.setRight(sqlTableSource);
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
                handleFrom(from, sortedTableInfos, sortedTableRelations, joinedTable, joinedTableRelations);
                oracleSelectQueryBlock.setFrom(from);


                // 处理where
                oracleSelectQueryBlock.setWhere(null);
                sortedTableRelations.removeAll(joinedTableRelations);
                if (sortedTableRelations.isEmpty()) {
                    return;
                }

                // 处理除外连接之外的所有
                List<SQLExpr> sqlExprs = new ArrayList<>();
                sqlExprs.addAll(sortedTableRelations.stream().map(TableRelation::getInnerRelation).filter(Objects::nonNull).collect(Collectors.toList()));
                sqlExprs.addAll(sortedTableRelations.stream().map(TableRelation::getCommonRelation).filter(Objects::nonNull).collect(Collectors.toList()));
                sqlExprs.addAll(sortedTableRelations.stream().map(TableRelation::getSqlExpr).filter(Objects::nonNull).collect(Collectors.toList()));
                if (sqlExprs.size() == 1) {
                    oracleSelectQueryBlock.setWhere(sqlExprs.get(0));
                } else if (!sqlExprs.isEmpty()) {
                    SQLBinaryOpExpr curr = where;
                    for (int i = sqlExprs.size() - 1; i > 0; i--) {
                        SQLExpr expr = sqlExprs.get(i);
                        SQLObject parent = expr.getParent();
                        if (parent instanceof SQLBinaryOpExpr) {
                            SQLBinaryOpExpr parentBinaryOpExpr = (SQLBinaryOpExpr) parent;
                            if (expr.equals(parentBinaryOpExpr.getLeft())) {
                                // 如果是父的左子节点，则取父的父的操作符
                                SQLObject grandParent = parentBinaryOpExpr.getParent();
                                if (grandParent instanceof SQLBinaryOpExpr) {
                                    curr.setOperator(((SQLBinaryOpExpr) grandParent).getOperator());
                                } else {
                                    curr.setOperator(SQLBinaryOperator.BooleanAnd);
                                }
                            } else {
                                // 如果是父的右子节点，则取父的操作符
                                curr.setOperator(parentBinaryOpExpr.getOperator());
                            }
                        } else {
                            curr.setOperator(SQLBinaryOperator.BooleanAnd);
                        }
                        curr.setRight(expr);
                        curr.setLeft(new SQLBinaryOpExpr());
                        curr = (SQLBinaryOpExpr) curr.getLeft();
                    }
                    ((SQLBinaryOpExpr) curr.getParent()).setLeft(sqlExprs.get(0));
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
                    joinedTable.add((((OracleSelectTableReference) left).getTableName() + " ").toLowerCase());
                } else {
                    joinedTable.add((((OracleSelectTableReference) left).getTableName() + " " + alias).toLowerCase());
                }
            }

            // 处理左表，左表为子查询的情况
            if (left instanceof OracleSelectSubqueryTableSource) {
                joinedTable.add((getSubqueryTableName() + " " + left.getAlias()).toLowerCase());
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
                    joinedTable.add((((OracleSelectTableReference) right).getTableName() + " ").toLowerCase());
                } else {
                    joinedTable.add((((OracleSelectTableReference) right).getTableName() + " " + alias).toLowerCase());
                }
            }
            // 如果右表为OracleSelectJoin
            if (right instanceof OracleSelectJoin) {
                handleFrom(right, sortedTableInfos, tableRelations, joinedTable, joinedTableRelations);
            }
            // 如果右表为子查询
            if (right instanceof OracleSelectSubqueryTableSource) {
                joinedTable.add((getSubqueryTableName() + " " + right.getAlias()).toLowerCase());
                preVisit(((OracleSelectSubqueryTableSource) right).getSelect().getQuery());
            }

            // 递归处理：中
            handleJoinCondition(selectJoin, sortedTableInfos, tableRelations, joinedTable, joinedTableRelations);
        }
    }

    private static String getSubqueryTableName() {
        // 加一个前缀，尽量将子查询置后
        return ("~" + OracleSelectSubqueryTableSource.class.getSimpleName()).toLowerCase();
    }

    private void getTableSources(SQLTableSource sqlTableSource, Map<String, String> aliasNameMap, Map<String, SQLTableSource> tableSourceMap) {
        if (sqlTableSource instanceof OracleSelectTableReference) {
            String alias = sqlTableSource.getAlias().toLowerCase();
            String tableName = ((OracleSelectTableReference) sqlTableSource).getTableName().toLowerCase();
            if (!StringUtils.isEmpty(alias)) {
                aliasNameMap.put(alias, tableName);
                tableSourceMap.put(tableName + " " + alias, sqlTableSource);
            } else {
                tableSourceMap.put(tableName + " ", sqlTableSource);
            }
        }
        if (sqlTableSource instanceof OracleSelectSubqueryTableSource) {
            String alias = sqlTableSource.getAlias().toLowerCase();
            aliasNameMap.put(alias, getSubqueryTableName());
            tableSourceMap.put(getSubqueryTableName() + " " + alias, sqlTableSource);
        }
        if (sqlTableSource instanceof OracleSelectJoin) {
            OracleSelectJoin selectJoin = (OracleSelectJoin) sqlTableSource;
            getTableSources(selectJoin.getLeft(), aliasNameMap, tableSourceMap);
            getTableSources(selectJoin.getRight(), aliasNameMap, tableSourceMap);
        }
    }

    private void handleJoinCondition(OracleSelectJoin sqlTableSource, List<TableInfo> sortedTableInfos, List<TableRelation> tableRelations, Set<String> joinedTable, List<TableRelation> joinedTableRelations) {
        // 获取关联的关系
        List<SQLBinaryOpExpr> innerRelations = new ArrayList<>();
        List<SQLBinaryOpExpr> outerRelations = new ArrayList<>();

        List<String> sortedTableNames = sortedTableInfos.stream().map(TableInfo::getTableName).collect(Collectors.toList());
        List<String> sortedAliases = sortedTableInfos.stream().map(TableInfo::getAlias).collect(Collectors.toList());

        for (TableRelation tableRelation : tableRelations) {
            if (joinedTableRelations.contains(tableRelation)) {
                continue;
            }

            if (joinedTable.contains(tableRelation.getPreTableNameAndAlias().toLowerCase()) && joinedTable.contains(tableRelation.getPostTableNameAndAlias().toLowerCase())) {
                // 说明是外连接
                if (tableRelation.getOuterRelation() != null) {
                    if (tableRelation.getOuterRelationType() == null) {
                        // 说明是使用(+)进行连接的
                        SQLBinaryOpExpr outerRelation = tableRelation.getOuterRelation();
                        // 这里的(+)都在右侧，因为之前被转换了
                        String leftName = getRelationColumnName(outerRelation.getLeft());
                        String rightName = getRelationColumnName(outerRelation.getRight());

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
            if (innerRelations.size() == 1) {
                sqlTableSource.setCondition(innerRelations.get(0));
            } else if (!innerRelations.isEmpty()) {
                SQLBinaryOpExpr conditions = new SQLBinaryOpExpr();
                SQLBinaryOpExpr curr = conditions;
                for (int i = innerRelations.size() - 1; i > 0; i--) {
                    SQLBinaryOpExpr expr = innerRelations.get(i);
                    SQLObject parent = expr.getParent();
                    if (parent instanceof SQLBinaryOpExpr) {
                        SQLBinaryOpExpr parentBinaryOpExpr = (SQLBinaryOpExpr) parent;
                        if (expr.equals(parentBinaryOpExpr.getLeft())) {
                            // 如果是父的左子节点，则取父的父的操作符
                            SQLObject grandParent = parentBinaryOpExpr.getParent();
                            if (grandParent instanceof SQLBinaryOpExpr) {
                                curr.setOperator(((SQLBinaryOpExpr) grandParent).getOperator());
                            } else {
                                curr.setOperator(SQLBinaryOperator.BooleanAnd);
                            }
                        } else {
                            // 如果是父的右子节点，则取父的操作符
                            curr.setOperator(parentBinaryOpExpr.getOperator());
                        }
                    } else {
                        curr.setOperator(SQLBinaryOperator.BooleanAnd);
                    }
                    curr.setRight(innerRelations.get(i));
                    curr.setLeft(new SQLBinaryOpExpr());
                    curr = (SQLBinaryOpExpr) curr.getLeft();
                }
                ((SQLBinaryOpExpr) curr.getParent()).setLeft(innerRelations.get(0));
                sqlTableSource.setCondition(conditions);
            }
        } else {
            // 如果是外连接
            if (outerRelations.size() == 1) {
                sqlTableSource.setCondition(outerRelations.get(0));
            } else if (!outerRelations.isEmpty()) {
                SQLBinaryOpExpr conditions = new SQLBinaryOpExpr();
                SQLBinaryOpExpr curr = conditions;
                for (int i = outerRelations.size() - 1; i > 0; i--) {
                    SQLBinaryOpExpr expr = outerRelations.get(i);
                    SQLObject parent = expr.getParent();
                    if (parent instanceof SQLBinaryOpExpr) {
                        SQLBinaryOpExpr parentBinaryOpExpr = (SQLBinaryOpExpr) parent;
                        if (expr.equals(parentBinaryOpExpr.getLeft())) {
                            // 如果是父的左子节点，则取父的父的操作符
                            SQLObject grandParent = parentBinaryOpExpr.getParent();
                            if (grandParent instanceof SQLBinaryOpExpr) {
                                curr.setOperator(((SQLBinaryOpExpr) parentBinaryOpExpr.getParent()).getOperator());
                            } else {
                                curr.setOperator(SQLBinaryOperator.BooleanAnd);
                            }
                        } else {
                            // 如果是父的右子节点，则取父的操作符
                            curr.setOperator(parentBinaryOpExpr.getOperator());
                        }
                    } else {
                        curr.setOperator(SQLBinaryOperator.BooleanAnd);
                    }
                    curr.setRight(expr);
                    curr.setLeft(new SQLBinaryOpExpr());
                    curr = (SQLBinaryOpExpr) curr.getLeft();
                }
                ((SQLBinaryOpExpr) curr.getParent()).setLeft(outerRelations.get(0));
                sqlTableSource.setCondition(conditions);
            }
        }
    }

    private void parseTableRelationsInFrom(SQLTableSource sqlTableSource, Map<String, String> aliasNameMap, Set<TableRelation> tableRelations) {
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

    private void parseTableRelationsInWhere(SQLBinaryOpExpr sqlBinaryOpExpr, Map<String, String> aliasNameMap, Set<TableRelation> tableRelations) {
        if (sqlBinaryOpExpr == null) {
            return;
        }

        SQLExpr left = sqlBinaryOpExpr.getLeft();
        SQLExpr right = sqlBinaryOpExpr.getRight();

        // 说明是普通条件，需要把条件保留在where中
        // 判断left和right的类型是不是属于SQLIntegerExpr、SQLCharExpr、SQLIdentifierExpr、SQLVariantRefExpr
        if (commonClasses.contains(left.getClass()) || commonClasses.contains(right.getClass())) {
            TableRelation tableRelation = new TableRelation();
            // 判断是否有a.column(+) = 1的场景
            if (left instanceof OracleOuterExpr) {
                // a.column(+) = 1 相当于 (a.column = 1 or a.column is null)
                SQLBinaryOpExpr leftExpr = new SQLBinaryOpExpr(((OracleOuterExpr) left).getExpr(), SQLBinaryOperator.Equality, right);
                SQLBinaryOpExpr rightExpr = new SQLBinaryOpExpr(((OracleOuterExpr) left).getExpr(), SQLBinaryOperator.Is, new SQLNullExpr());
                SQLBinaryOpExpr binaryOpExpr = new SQLBinaryOpExpr(leftExpr, SQLBinaryOperator.BooleanOr, rightExpr);
                binaryOpExpr.setParenthesized(true);
                tableRelation.setCommonRelation(binaryOpExpr);
                tableRelations.add(tableRelation);
            } else if (right instanceof OracleOuterExpr) {
                // 将(+)放到左边
                sqlBinaryOpExpr.setRight(left);
                sqlBinaryOpExpr.setLeft(right);
                parseTableRelationsInWhere(sqlBinaryOpExpr, aliasNameMap, tableRelations);
            } else {
                // 加入tableRelations之前，判断其父是否有括号
                SQLObject parent = sqlBinaryOpExpr.getParent();
                if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                    // 如果有括号，判断其是否已经加入
                    handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
                } else {
                    tableRelation.setCommonRelation(sqlBinaryOpExpr);
                    tableRelations.add(tableRelation);
                }
            }
            return;
        }

        // 处理Exists语句
        if (left instanceof SQLExistsExpr) {
            TableRelation tableRelation = new TableRelation();
            SQLSelectQuery sqlSelectQuery = ((SQLExistsExpr) left).getSubQuery().getQuery();
            preVisit(sqlSelectQuery);
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setSqlExpr(left);
                tableRelations.add(tableRelation);
            }
        }

        if (right instanceof SQLExistsExpr) {
            TableRelation tableRelation = new TableRelation();
            SQLSelectQuery sqlSelectQuery = ((SQLExistsExpr) right).getSubQuery().getQuery();
            preVisit(sqlSelectQuery);
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setSqlExpr(right);
                tableRelations.add(tableRelation);
            }
        }

        // 处理In语句
        if (left instanceof SQLInSubQueryExpr) {
            TableRelation tableRelation = new TableRelation();
            SQLSelectQuery sqlSelectQuery = ((SQLInSubQueryExpr) left).getSubQuery().getQuery();
            preVisit(sqlSelectQuery);
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setSqlExpr(left);
                tableRelations.add(tableRelation);
            }
        }

        if (right instanceof SQLInSubQueryExpr) {
            TableRelation tableRelation = new TableRelation();
            SQLSelectQuery sqlSelectQuery = ((SQLInSubQueryExpr) right).getSubQuery().getQuery();
            preVisit(sqlSelectQuery);
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setSqlExpr(right);
                tableRelations.add(tableRelation);
            }
        }

        // 处理is NULL / is not NULL
        if (left instanceof SQLNullExpr) {
            TableRelation tableRelation = new TableRelation();
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setCommonRelation(sqlBinaryOpExpr);
                tableRelations.add(tableRelation);
            }
        }

        if (right instanceof SQLNullExpr) {
            TableRelation tableRelation = new TableRelation();
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setCommonRelation(sqlBinaryOpExpr);
                tableRelations.add(tableRelation);
            }
        }

        if (left instanceof SQLQueryExpr) {
            SQLQueryExpr queryExpr = (SQLQueryExpr) left;
            preVisit(queryExpr);
            TableRelation tableRelation = new TableRelation();
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = left.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setCommonRelation(sqlBinaryOpExpr);
                tableRelations.add(tableRelation);
            }
        }

        if (right instanceof SQLQueryExpr) {
            SQLQueryExpr queryExpr = (SQLQueryExpr) right;
            preVisit(queryExpr);
            TableRelation tableRelation = new TableRelation();
            // 加入tableRelations之前，判断其父是否有括号
            SQLObject parent = right.getParent();
            if (parent instanceof SQLBinaryOpExpr && ((SQLBinaryOpExpr) parent).isParenthesized()) {
                // 如果有括号，判断其是否已经加入
                handleParenthesized(sqlBinaryOpExpr, tableRelations, tableRelation, (SQLBinaryOpExpr) parent);
            } else {
                tableRelation.setCommonRelation(sqlBinaryOpExpr);
                tableRelations.add(tableRelation);
            }
        }

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
            String leftName = getRelationColumnName(left);
            String rightName = getRelationColumnName(right);

            TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
            tableRelation.setOuterRelation(sqlBinaryOpExpr);
            tableRelations.add(tableRelation);
        }

        if (left instanceof SQLMethodInvokeExpr || right instanceof SQLMethodInvokeExpr) {
            // 向上找到连接关系
            SQLObject parent = sqlBinaryOpExpr.getParent();
            while (!(parent instanceof OracleSelectJoin) && !(parent instanceof OracleSelectQueryBlock)) {
                parent = parent.getParent();
            }
            if (parent instanceof OracleSelectJoin) {
                OracleSelectJoin selectJoin = (OracleSelectJoin) parent;
                if (selectJoin.getJoinType().equals(SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN) || selectJoin.getJoinType().equals(SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN)) {
                    // 如果是左右连接
                    String leftName = getRelationColumnName(left);
                    String rightName = getRelationColumnName(right);

                    TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
                    tableRelation.setOuterRelation(sqlBinaryOpExpr);
                    tableRelation.setOuterRelationType(selectJoin.getJoinType());

                    SQLTableSource rightTable = ((OracleSelectJoin) parent).getRight();
                    String leftTableName = "";
                    String leftTableAlise = "";
                    String rightTableName = "";
                    String rightTableAlias = rightTable.getAlias();
                    if (rightTable instanceof OracleSelectTableReference) {
                        rightTableName = ((SQLIdentifierExpr) ((OracleSelectTableReference) rightTable).getExpr()).getName().toLowerCase();
                    }
                    if (rightTable instanceof OracleSelectSubqueryTableSource) {
                        rightTableName = getSubqueryTableName();
                    }

                    String preTableName = tableRelation.getPreTable().getTableName().toLowerCase();
                    String preAlias = tableRelation.getPreTable().getAlias().toLowerCase();
                    String postTableName = tableRelation.getPostTable().getTableName().toLowerCase();
                    String postAlias = tableRelation.getPostTable().getAlias().toLowerCase();

                    if (rightTableName.equalsIgnoreCase(preTableName)) {
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
            String leftName = getRelationColumnName(left);
            String rightName = getRelationColumnName(right);

            if (StringUtils.isEmpty(leftName) || StringUtils.isEmpty(rightName)) {
                throw new RuntimeException("暂不支持复杂的连接场景：" + sqlBinaryOpExpr);
            }

            TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
            tableRelation.setInnerRelation(sqlBinaryOpExpr);
            tableRelations.add(tableRelation);
        }

        if (left instanceof SQLPropertyExpr && right instanceof SQLPropertyExpr) {
            // 向上找到连接关系
            SQLObject parent = sqlBinaryOpExpr.getParent();
            while (!(parent instanceof OracleSelectJoin) && !(parent instanceof OracleSelectQueryBlock)) {
                parent = parent.getParent();
            }
            if (parent instanceof OracleSelectJoin) {
                OracleSelectJoin selectJoin = (OracleSelectJoin) parent;
                if (selectJoin.getJoinType().equals(SQLJoinTableSource.JoinType.LEFT_OUTER_JOIN) || selectJoin.getJoinType().equals(SQLJoinTableSource.JoinType.RIGHT_OUTER_JOIN)) {
                    // 如果是左右连接
                    String leftName = getRelationColumnName(left);
                    String rightName = getRelationColumnName(right);

                    TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
                    tableRelation.setOuterRelation(sqlBinaryOpExpr);
                    tableRelation.setOuterRelationType(selectJoin.getJoinType());

                    SQLTableSource rightTable = ((OracleSelectJoin) parent).getRight();
                    String leftTableName = "";
                    String leftTableAlise = "";
                    String rightTableName = "";
                    String rightTableAlias = rightTable.getAlias();
                    if (rightTable instanceof OracleSelectTableReference) {
                        rightTableName = ((SQLIdentifierExpr) ((OracleSelectTableReference) rightTable).getExpr()).getName().toLowerCase();
                    }
                    if (rightTable instanceof OracleSelectSubqueryTableSource) {
                        rightTableName = getSubqueryTableName();
                    }

                    String preTableName = tableRelation.getPreTable().getTableName().toLowerCase();
                    String preAlias = tableRelation.getPreTable().getAlias().toLowerCase();
                    String postTableName = tableRelation.getPostTable().getTableName().toLowerCase();
                    String postAlias = tableRelation.getPostTable().getAlias().toLowerCase();

                    if (rightTableName.equalsIgnoreCase(preTableName)) {
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
            String leftName = getRelationColumnName(left);
            String rightName = getRelationColumnName(right);

            TableRelation tableRelation = getTableRelation(aliasNameMap, leftName, rightName);
            tableRelation.setInnerRelation(sqlBinaryOpExpr);
            tableRelations.add(tableRelation);
        }
    }

    private String getRelationColumnName(SQLExpr sqlExpr) {
        if (sqlExpr instanceof SQLPropertyExpr) {
            return ((SQLIdentifierExpr) ((SQLPropertyExpr) sqlExpr).getOwner()).getName();
        }

        // 递归获取第一个SQLPropertyExpr的name
        if (sqlExpr instanceof SQLMethodInvokeExpr) {
            for (SQLExpr argument : ((SQLMethodInvokeExpr) sqlExpr).getArguments()) {
                String expr = getRelationColumnName(argument);
                if (expr != null) {
                    return expr;
                }
            }
        }

        if (sqlExpr instanceof OracleOuterExpr) {
            return getRelationColumnName(((OracleOuterExpr) sqlExpr).getExpr());
        }

        return null;
    }

    private void handleParenthesized(SQLBinaryOpExpr sqlBinaryOpExpr, Set<TableRelation> tableRelations, TableRelation tableRelation, SQLBinaryOpExpr parent) {
        // 如果有括号，判断其是否已经加入
        List<SQLBinaryOpExpr> commonRelations = tableRelations.stream().map(TableRelation::getCommonRelation).filter(Objects::nonNull).collect(Collectors.toList());
        for (SQLBinaryOpExpr commonRelation : commonRelations) {
            // 说明已经加入
            if (sqlBinaryOpExpr.equals(commonRelation.getLeft()) || sqlBinaryOpExpr.equals(commonRelation.getRight())) {
                return;
            }
        }
        // 说明没有加入，直接加入整个父，但需要先处理之前错加的情况
        tableRelation.setCommonRelation(parent);
        // 遍历获取每个SQLBinaryOpExpr
        List<SQLBinaryOpExpr> SQLBinaryOpExprList = new ArrayList<>();
        getSQLBinaryOpExpr(tableRelation.getCommonRelation(), SQLBinaryOpExprList);
        // 判断之前加入的relation是否包含于当前relation
        Iterator<TableRelation> iterator = tableRelations.iterator();
        while (iterator.hasNext()) {
            TableRelation relation = iterator.next();
            List<SQLBinaryOpExpr> currRelationBinaryOpExprList = new ArrayList<>();
            getSQLBinaryOpExpr(relation.getCommonRelation(), currRelationBinaryOpExprList);
            // 如果有交集，需要将该relation删掉
            if (SQLBinaryOpExprList.stream().anyMatch(currRelationBinaryOpExprList::contains)) {
                iterator.remove();
            }
        }
        // 最后直接加入整个父
        tableRelations.add(tableRelation);
    }

    private void getSQLBinaryOpExpr(SQLBinaryOpExpr sqlBinaryOpExpr, List<SQLBinaryOpExpr> sqlBinaryOpExprList) {
        if (sqlBinaryOpExpr == null) {
            return;
        }

        SQLExpr left = sqlBinaryOpExpr.getLeft();
        SQLExpr right = sqlBinaryOpExpr.getRight();

        if (commonClasses.contains(left.getClass()) || commonClasses.contains(right.getClass())) {
            sqlBinaryOpExprList.add(sqlBinaryOpExpr);
            return;
        }

        if (left instanceof SQLBinaryOpExpr) {
            getSQLBinaryOpExpr((SQLBinaryOpExpr) left, sqlBinaryOpExprList);
        }
        if (right instanceof SQLBinaryOpExpr) {
            getSQLBinaryOpExpr((SQLBinaryOpExpr) right, sqlBinaryOpExprList);
        }
    }

    private static TableRelation getTableRelation(Map<String, String> aliasNameMap, String leftName, String rightName) {
        String leftTableName = "";
        String leftAlias = "";
        String rightTableName = "";
        String rightAlias = "";

        if (aliasNameMap.containsKey(leftName.toLowerCase())) {
            // 说明leftName是别名
            leftAlias = leftName;
            leftTableName = aliasNameMap.get(leftName.toLowerCase());
        } else {
            // 说明leftName是表名，别名为空
            leftTableName = leftName;
        }

        if (aliasNameMap.containsKey(rightName.toLowerCase())) {
            // 说明rightName是别名
            rightAlias = rightName;
            rightTableName = aliasNameMap.get(rightName.toLowerCase());
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
