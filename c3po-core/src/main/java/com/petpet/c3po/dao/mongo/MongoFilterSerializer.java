package com.petpet.c3po.dao.mongo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.petpet.c3po.api.model.helper.Filter;
import com.petpet.c3po.api.model.helper.FilterCondition;

/**
 * The {@link MongoFilterSerializer} translates a filter object to a
 * {@link DBObject} query, so that the dataset is first filtered and then the
 * persistence layer function is applied.
 * 
 * @author Petar Petrov <me@petarpetrov.org>
 * 
 */
public class MongoFilterSerializer {

  private static final String[] EXCLUDE = { "_id", "uid", "collection", "name" };
  
  private static final BasicDBObject EXISTS = new BasicDBObject("$exists", true);

  /**
   * Serializes the given filter according to the strategy proposed here:
   * {@link Filter}. If the filter is null, then an empty {@link DBObject} is
   * returned.
   * 
   * @param filter
   *          the filter to serialize.
   * @return the Mongo {@link DBObject}
   */
  public DBObject serialize(Filter filter) {
    DBObject result = new BasicDBObject();

    if (filter != null) {
      List<FilterCondition> conditions = filter.getConditions();
      Map<String, Integer> distinctFields = this.getDistinctFields(conditions);
      List<BasicDBObject> and = new ArrayList<BasicDBObject>();

      for (String field : distinctFields.keySet()) {

        if (distinctFields.get(field) == 1) {

          Object val = this.getValueForField(field, conditions.toArray(new FilterCondition[conditions.size()]));
          and.add(new BasicDBObject(this.mapFieldToProperty(field, val), val));

        } else {

          BasicDBObject orQuery = this.getOrQuery(conditions, field);
          and.add(orQuery);

        }

      }
      
      if (and.size() > 0) {
        result.put("$and", and);
      }

    }

    return result;
  }

  /**
   * Wraps the field within a metadata.[field].value if necessary, so that it
   * corresponds to the current element structure.
   * 
   * @param f
   *          the field to wrap
   * @return the wrapped field.
   */
  public String mapFieldToProperty(String f, Object value) {
    if (Arrays.asList(EXCLUDE).contains(f)) {
      return f;
    }

    String result = "metadata." + f;
    return  (value.equals(EXISTS)) ? result : result + ".value";
  }

  /**
   * Gets a {@link DBObject} that represents an or condition with all the values
   * of the given field.
   * 
   * @param conditions
   *          the filter conditions to look at.
   * @param field
   *          the field that has to be or concatenated.
   * @return the or condition.
   */
  private BasicDBObject getOrQuery(List<FilterCondition> conditions, String field) {
    List<BasicDBObject> or = new ArrayList<BasicDBObject>();

    for (FilterCondition fc : conditions) {
      if (field.equals(fc.getField())) {
        Object val = this.getValueForField(field, fc);
        or.add(new BasicDBObject(this.mapFieldToProperty(field, val), val));
      }
    }

    return new BasicDBObject("$or", or);
  }

  /**
   * Gets the distinct fields and the number of values for these fields within
   * the passed conditions.
   * 
   * @param conditions
   *          the conditions to look at.
   * @return a map of the distinct fields and the number of occurrences.
   */
  private Map<String, Integer> getDistinctFields(List<FilterCondition> conditions) {
    Map<String, Integer> distinctFields = new HashMap<String, Integer>();

    for (FilterCondition fc : conditions) {
      Integer integer = distinctFields.get(fc.getField());
      int res = (integer == null) ? 0 : integer;
      distinctFields.put(fc.getField(), ++res);
    }

    return distinctFields;
  }

  /**
   * Gets the first value for a given field.
   * 
   * @param conditions
   *          the conditions to look at.
   * @param field
   *          the field to look at.
   * @return the value or null.
   */
  private Object getValueForField(String field, FilterCondition... conditions) {
    for (FilterCondition fc : conditions) {
      if (fc.getField().equals(field)) {
        Object val = fc.getValue();
        return (val == null) ? EXISTS : val;
      }
        
    }

    return null;
  }
}
