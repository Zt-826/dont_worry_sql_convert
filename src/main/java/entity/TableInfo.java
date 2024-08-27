package entity;

import java.util.Objects;

public class TableInfo implements Comparable<TableInfo> {
    private String tableName;

    private String alias;

    public TableInfo(String tableName, String alias) {
        this.tableName = tableName;
        if (alias == null) {
            this.alias = "";
        } else {
            this.alias = alias;
        }
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Override
    public int compareTo(TableInfo o) {
        // 以alias + " " + tableName进行排序，字典序小的排在前边
        String name = alias.toLowerCase()  + " "+ tableName.toLowerCase();
        return name.compareTo(o.alias.toLowerCase() + " " + o.tableName.toLowerCase());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableInfo tableInfo1 = (TableInfo) o;
        return Objects.equals(tableName, tableInfo1.tableName) && Objects.equals(alias, tableInfo1.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, alias);
    }
}