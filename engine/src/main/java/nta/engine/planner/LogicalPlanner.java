package nta.engine.planner;

import nta.catalog.Column;
import nta.catalog.Schema;
import nta.catalog.SchemaUtil;
import nta.catalog.proto.CatalogProtos.DataType;
import nta.engine.Context;
import nta.engine.exec.eval.BinaryEval;
import nta.engine.exec.eval.EvalNode;
import nta.engine.exec.eval.EvalNode.Type;
import nta.engine.exec.eval.FieldEval;
import nta.engine.parser.CreateIndexStmt;
import nta.engine.parser.CreateTableStmt;
import nta.engine.parser.ParseTree;
import nta.engine.parser.QueryAnalyzer;
import nta.engine.parser.QueryBlock;
import nta.engine.parser.QueryBlock.FromTable;
import nta.engine.parser.QueryBlock.JoinClause;
import nta.engine.parser.QueryBlock.Target;
import nta.engine.planner.logical.CreateIndexNode;
import nta.engine.planner.logical.CreateTableNode;
import nta.engine.planner.logical.StoreTableNode;
import nta.engine.planner.logical.EvalExprNode;
import nta.engine.planner.logical.GroupbyNode;
import nta.engine.planner.logical.JoinNode;
import nta.engine.planner.logical.LogicalNode;
import nta.engine.planner.logical.LogicalRootNode;
import nta.engine.planner.logical.ProjectionNode;
import nta.engine.planner.logical.ScanNode;
import nta.engine.planner.logical.SelectionNode;
import nta.engine.planner.logical.SortNode;
import nta.engine.query.exception.NotSupportQueryException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This class creates a logical plan from a parse tree ({@link QueryBlock})
 * generated by {@link QueryAnalyzer}.
 * 
 * @author Hyunsik Choi
 *
 * @see QueryBlock
 */
public class LogicalPlanner {
  private static Log LOG = LogFactory.getLog(LogicalPlanner.class);

  private LogicalPlanner() {
  }

  /**
   * This generates a logical plan.
   * 
   * @param query a parse tree
   * @return a initial logical plan
   */
  public static LogicalNode createPlan(Context ctx, ParseTree query) {
    LogicalNode plan = null;
    
    switch(query.getType()) {
    case SELECT:
      LOG.info("Planning select statement");
      QueryBlock select = (QueryBlock) query;
      plan = buildSelectPlan(ctx, select);
      break;
      
    case CREATE_INDEX:
      LOG.info("Planning create index statement");
      CreateIndexStmt createIndex = (CreateIndexStmt) query;
      plan = buildCreateIndexPlan(ctx, createIndex);
      break;

    case CREATE_TABLE:
      LOG.info("Planning store statement");
      CreateTableStmt createTable = (CreateTableStmt) query;
      plan = buildCreateTablePlan(ctx, createTable);
      break;

    default:;
    throw new NotSupportQueryException(query.toString());
    }
    
    LogicalRootNode root = new LogicalRootNode();
    root.setInputSchema(plan.getOutputSchema());
    root.setOutputSchema(plan.getOutputSchema());
    root.setSubNode(plan);
    
    return root;
  }
  
  private static LogicalNode buildCreateIndexPlan(Context ctx,
      CreateIndexStmt stmt) {    
    return new CreateIndexNode(stmt);    
  }
  
  private static LogicalNode buildCreateTablePlan(Context ctx, 
      CreateTableStmt query) {
    LogicalNode node = null;
    if (query.hasDefinition())  {
      CreateTableNode createTable = 
          new CreateTableNode(query.getTableName(), query.getSchema(), 
              query.getStoreType(), query.getPath());
      createTable.setInputSchema(query.getSchema());
      createTable.setOutputSchema(query.getSchema());
      node = createTable;
    } else if (query.hasSelectStmt()) {
      LogicalNode subNode = buildSelectPlan(ctx, query.getSelectStmt());
      
      StoreTableNode storeNode = new StoreTableNode(query.getTableName());
      storeNode.setInputSchema(subNode.getOutputSchema());
      storeNode.setOutputSchema(subNode.getOutputSchema());
      storeNode.setSubNode(subNode);
      node = storeNode;
    }
    
    return node;
  }
  
  /**
   * ^(SELECT from_clause? where_clause? groupby_clause? selectList)
   * 
   * @param query
   * @return
   */
  private static LogicalNode buildSelectPlan(Context ctx, QueryBlock query) {
    LogicalNode subroot = null;
    if(query.hasFromClause()) {
      if (query.hasJoinClause()) {
        subroot = createExplicitJoinTree(ctx, query.getJoinClause());
      } else {
        subroot = createImplicitJoinTree(ctx, query.getFromTables());
      }
    } else {
      subroot = new EvalExprNode(query.getTargetList());
      subroot.setOutputSchema(getProjectedSchema(ctx, query.getTargetList()));
      return subroot;
    }
    
    if(query.hasWhereClause()) {
      SelectionNode selNode = 
          new SelectionNode(query.getWhereCondition());
      selNode.setSubNode(subroot);
      selNode.setInputSchema(subroot.getOutputSchema());
      selNode.setOutputSchema(selNode.getInputSchema());
      subroot = selNode;
    }
    
    if(query.hasAggregation()) {
      GroupbyNode groupbyNode = null;
      if (query.hasGroupbyClause()) {
        groupbyNode = new GroupbyNode(query.getGroupFields());
        if(query.hasHavingCond())
          groupbyNode.setHavingCondition(query.getHavingCond());
      } else {
        // when aggregation functions are used without grouping fields
        groupbyNode = new GroupbyNode(new Column [] {});
      }
      groupbyNode.setTargetList(ctx.getTargetList());      
      groupbyNode.setSubNode(subroot);
      groupbyNode.setInputSchema(subroot.getOutputSchema());      
      Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
      groupbyNode.setOutputSchema(outSchema);
      subroot = groupbyNode;
    }
    
    if(query.hasOrderByClause()) {
      SortNode sortNode = new SortNode(query.getSortKeys());
      sortNode.setSubNode(subroot);
      sortNode.setInputSchema(subroot.getOutputSchema());
      sortNode.setOutputSchema(sortNode.getInputSchema());
      subroot = sortNode;
    }
    
    ProjectionNode prjNode = null;
    if (query.getProjectAll()) {
      prjNode = new ProjectionNode();
      prjNode.setSubNode(subroot);
      subroot = prjNode;
    } else {
      if (query.hasAggregation()) {           
      } else {
        prjNode = new ProjectionNode(query.getTargetList());
        if (subroot != null) { // false if 'no from' statement
          prjNode.setSubNode(subroot);
        }
        prjNode.setInputSchema(subroot.getOutputSchema());
        Schema projected = getProjectedSchema(ctx, query.getTargetList());
        prjNode.setOutputSchema(projected);
        subroot = prjNode;
      }
    }    
    
    return subroot;
  }
  
  private static LogicalNode createExplicitJoinTree(Context ctx, 
      JoinClause joinClause) {
    JoinNode join = null;
    
    join = new JoinNode(joinClause.getJoinType(),
        new ScanNode(joinClause.getLeft()));
    if (joinClause.hasJoinQual()) {
      join.setJoinQual(joinClause.getJoinQual());
    } else if (joinClause.hasJoinColumns()) { 
      // for using clause of explicit join
      // TODO - to be implemented. Now, tajo only support 'ON' join clause.
    }
    
    if (joinClause.hasRightJoin()) {
      join.setInner(createExplicitJoinTree(ctx, joinClause.getRightJoin()));
    } else {
      join.setInner(new ScanNode(joinClause.getRight()));      
    }
    
    // Determine Join Schemas
    Schema merged = null;
    if (join.getJoinType() == JoinType.NATURAL) {
      merged = getNaturalJoin(join.getOuterNode(), join.getInnerNode());
    } else {
      merged = SchemaUtil.merge(join.getOuterNode().getOutputSchema(),
          join.getInnerNode().getOutputSchema());
    }
    
    join.setInputSchema(merged);
    join.setOutputSchema(merged);
    
    // Determine join quals
    // if natural join, should have the equi join conditions on common columns
    if (join.getJoinType() == JoinType.NATURAL) {
      Schema leftSchema = join.getOuterNode().getOutputSchema();
      Schema rightSchema = join.getInnerNode().getOutputSchema();
      Schema commons = SchemaUtil.getCommons(
          leftSchema, rightSchema);
      EvalNode njCond = getNJCond(leftSchema, rightSchema, commons);
      join.setJoinQual(njCond);
    } else if (joinClause.hasJoinQual()) { 
      // otherwise, the given join conditions are set
      join.setJoinQual(joinClause.getJoinQual());
    }
    
    return join;
  }
  
  private static EvalNode getNJCond(Schema outer, Schema inner, Schema commons) {
    EvalNode njQual = null;
    EvalNode equiQual = null;
    
    Column leftJoinKey;
    Column rightJoinKey;
    for (Column common : commons.getColumns()) {
      leftJoinKey = outer.getColumnByName(common.getColumnName());
      rightJoinKey = inner.getColumnByName(common.getColumnName());
      equiQual = new BinaryEval(Type.EQUAL, 
          new FieldEval(leftJoinKey), new FieldEval(rightJoinKey));
      if (njQual == null) {
        njQual = equiQual;
      } else {
        njQual = new BinaryEval(Type.AND,
            njQual, equiQual);
      }
    }
    
    return njQual;
  }
  
  private static LogicalNode createImplicitJoinTree(Context ctx, 
      FromTable [] tables) {
    LogicalNode subroot = null;
    
    subroot = new ScanNode(tables[0]);
    Schema joinSchema = null;
    if(tables.length > 1) {    
      for(int i=1; i < tables.length; i++) {
        JoinNode join = new JoinNode(JoinType.CROSS_JOIN, 
            subroot, new ScanNode(tables[i]));
        joinSchema = SchemaUtil.merge(
            join.getOuterNode().getOutputSchema(),
            join.getInnerNode().getOutputSchema());
        join.setInputSchema(joinSchema);
        join.setOutputSchema(joinSchema);
        subroot = join;
      }
    }
    
    return subroot;
  }
  
  public static Schema getProjectedSchema(Context ctx, Target [] targets) {
    Schema projected = new Schema();
    for(Target t : targets) {
      DataType type = t.getEvalTree().getValueType();
      String name = null;
      if (t.hasAlias()) {
        name = t.getAlias();
      } else if (t.getEvalTree().getName().equals("?")) {
        name = ctx.getUnnamedColumn();
      } else {
        name = t.getEvalTree().getName();
      }      
      projected.addColumn(name,type);
    }
    
    return projected;
  }
  
  private static Schema getNaturalJoin(LogicalNode outer, LogicalNode inner) {
    Schema joinSchema = new Schema();
    Schema commons = SchemaUtil.getCommons(outer.getOutputSchema(),
        inner.getOutputSchema());
    joinSchema.addColumns(commons);
    for (Column c : outer.getOutputSchema().getColumns()) {
      for (Column common : commons.getColumns()) {
        if (!common.getColumnName().equals(c.getColumnName())) {
          joinSchema.addColumn(c);
        }
      }
    }

    for (Column c : inner.getOutputSchema().getColumns()) {
      for (Column common : commons.getColumns()) {
        if (!common.getColumnName().equals(c.getColumnName())) {
          joinSchema.addColumn(c);
        }
      }
    }
    return joinSchema;
  }
}