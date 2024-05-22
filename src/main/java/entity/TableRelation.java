package entity;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;

import java.util.Objects;

public class TableRelation implements Comparable<TableRelation> {
    /**
     * 用来做关联关系排序，字典序小的为preTable
     */
    private TableInfo preTable;

    /**
     * 用来做关联关系排序，字典序大的为postTable
     */
    private TableInfo postTable;

    /**
     * 用来记录左右连接时的左表，配合commonRelation字段
     */
    private TableInfo leftTable;

    /**
     * 用来记录左右连接时的右表，配合commonRelation字段
     */
    private TableInfo rightTable;

    private SQLBinaryOpExpr outerRelation;

    private SQLJoinTableSource.JoinType outerRelationType;

    private SQLBinaryOpExpr innerRelation;

    private SQLBinaryOpExpr commonRelation;

    private SQLExpr sqlExpr;

    public TableRelation(String leftName, String leftAlias, String rightName, String rightAlias) {
        // 根据入参的字典序决定preTable和postTable，字典序小的是preTable，字典序大的是postTable
        String left = leftAlias + " " + leftName;
        String right = rightAlias + " " + rightAlias;
        if (left.compareTo(right) < 0) {
            this.preTable = new TableInfo(leftName, leftAlias);
            this.postTable = new TableInfo(rightName, rightAlias);
        } else {
            this.preTable = new TableInfo(rightName, rightAlias);
            this.postTable = new TableInfo(leftName, leftAlias);
        }
    }

    public TableRelation() {
        this("", "", "", "");
    }

    public TableInfo getPreTable() {
        return preTable;
    }

    public void setPreTable(TableInfo preTable) {
        this.preTable = preTable;
    }

    public String getPreTableNameAndAlias() {
        return preTable.getTableName() + " " + preTable.getAlias();
    }

    public TableInfo getPostTable() {
        return postTable;
    }

    public String getPostTableNameAndAlias() {
        return postTable.getTableName() + " " + postTable.getAlias();
    }

    public void setPostTable(TableInfo postTable) {
        this.postTable = postTable;
    }

    public TableInfo getLeftTable() {
        return leftTable;
    }

    public void setLeftTable(TableInfo leftTable) {
        this.leftTable = leftTable;
    }

    public TableInfo getRightTable() {
        return rightTable;
    }

    public void setRightTable(TableInfo rightTable) {
        this.rightTable = rightTable;
    }

    public SQLBinaryOpExpr getOuterRelation() {
        return outerRelation;
    }

    public void setOuterRelation(SQLBinaryOpExpr outerRelation) {
        this.outerRelation = outerRelation;
    }

    public SQLBinaryOpExpr getInnerRelation() {
        return innerRelation;
    }

    public void setInnerRelation(SQLBinaryOpExpr innerRelation) {
        this.innerRelation = innerRelation;
    }

    public SQLBinaryOpExpr getCommonRelation() {
        return commonRelation;
    }

    public void setCommonRelation(SQLBinaryOpExpr commonRelation) {
        this.commonRelation = commonRelation;
    }

    public SQLJoinTableSource.JoinType getOuterRelationType() {
        return outerRelationType;
    }

    public void setOuterRelationType(SQLJoinTableSource.JoinType outerRelationType) {
        this.outerRelationType = outerRelationType;
    }

    public SQLExpr getSqlExpr() {
        return sqlExpr;
    }

    public void setSqlExpr(SQLExpr sqlExpr) {
        this.sqlExpr = sqlExpr;
    }

    @Override
    public int compareTo(TableRelation o) {
        // 先根据的preTable比大小，再根据postTable比大小
        if (preTable.equals(o.getPreTable())) {
            return postTable.compareTo(o.getPostTable());
        } else {
            return preTable.compareTo(o.getPreTable());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableRelation that = (TableRelation) o;
        return Objects.equals(preTable, that.preTable) && Objects.equals(postTable, that.postTable) && Objects.equals(leftTable, that.leftTable) && Objects.equals(rightTable, that.rightTable) && Objects.equals(outerRelation, that.outerRelation) && outerRelationType == that.outerRelationType && Objects.equals(innerRelation, that.innerRelation) && Objects.equals(commonRelation, that.commonRelation) && Objects.equals(sqlExpr, that.sqlExpr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(preTable, postTable, leftTable, rightTable, outerRelation, outerRelationType, innerRelation, commonRelation, sqlExpr);
    }
}
