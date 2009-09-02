/*
 * Copyright (c) 2007-2008, Hazel Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.query;

import com.hazelcast.core.MapEntry;
import com.hazelcast.nio.DataSerializable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class Predicates {
    public static Predicate eq(final Expression first, final Expression second) {
        return new EqualPredicate(first, second);
    }

    public static class GreaterLessPredicate extends EqualPredicate implements RangedPredicate{
        boolean equal = false;
        boolean less = false;

        public GreaterLessPredicate() {
        }

        public GreaterLessPredicate(Expression first, Expression second, boolean equal, boolean less) {
            super(first, second);
            this.equal = equal;
            this.less = less;
        }

        public GreaterLessPredicate(Expression first, Object second, boolean equal, boolean less) {
            super(first, second);
            this.equal = equal;
            this.less = less;
        }

        public boolean apply(MapEntry entry) {
            int expectedResult = (less) ? -1 : 1;
            Expression<Comparable> cFirst = (Expression<Comparable>) first;
            if (secondIsExpression) {
                return cFirst.getValue(entry).compareTo(((Expression) second).getValue(entry)) == expectedResult;
            } else {
                return cFirst.getValue(entry).compareTo(second) == expectedResult;
            }
        }

        public RangeType getRangeType() {
            if (less) {
                return (equal) ? RangeType.LESS_EQUAL : RangeType.LESS;
            } else {
                return (equal) ? RangeType.GREATER_EQUAL : RangeType.GREATER;
            }
        }

        public Object getFrom() {
            return null;
        }

        public Object getTo() {
            return null;  
        }
    }

    public static class BetweenPredicate extends EqualPredicate implements RangedPredicate{
        Object to;

        public BetweenPredicate(Expression first, Expression second, Object to) {
            super(first, second);
            this.to = to;
        }

        public BetweenPredicate(Expression first, Object second, Object to) {
            super(first, second);
            this.to = to;
        }

        public boolean apply(MapEntry entry) {
            Expression<Comparable> cFirst = (Expression<Comparable>) first;
            Comparable firstValue = cFirst.getValue(entry);
            Comparable fromValue = (Comparable) second;
            Comparable toValue = (Comparable) to;
            if (firstValue == null || fromValue == null || toValue == null) return false;
            return firstValue.compareTo(fromValue) >= 0 && firstValue.compareTo(toValue) <=0;
        }

        public RangeType getRangeType() {
            return RangeType.BETWEEN;
        }

        public Object getFrom() {
            return second;
        }

        public Object getTo() {
            return to;
        }
    }



    public static class EqualPredicate extends AbstractPredicate implements IndexAwarePredicate, IndexedPredicate {
        Expression first;
        Object second;
        protected boolean secondIsExpression = true;

        public EqualPredicate() {
        }

        public EqualPredicate(Expression first, Expression second) {
            this.first = first;
            this.second = second;
        }

        public EqualPredicate(Expression first, Object second) {
            this.first = first;
            this.second = second;
            this.secondIsExpression = false;
        }

        public boolean apply(MapEntry entry) {
            if (secondIsExpression) {
                return first.getValue(entry).equals(((Expression) second).getValue(entry));
            } else {
                return first.getValue(entry).equals(second);
            }
        }

        public void collectIndexedPredicates(List<IndexedPredicate> lsIndexPredicates) {
            if (!secondIsExpression && first instanceof GetExpression) {
                lsIndexPredicates.add(this);
            }
        }

        public String getIndexName() {
            return ((GetExpression) first).getMethodName();
        }

        public boolean isRanged() {
            return false;
        }

        public Object getValue() {
            return second;
        }

        public void writeData(DataOutput out) throws IOException {
            writeDataSerializable(out, (DataSerializable) first);
            out.writeBoolean(secondIsExpression);
            if (secondIsExpression) {
                writeDataSerializable(out, (DataSerializable) second);
            } else {
                if (second instanceof Number) {
                    out.writeByte(1);
                    out.writeLong(((Number) second).longValue());
                } else if (second instanceof String) {
                    out.writeByte(2);
                    out.writeUTF((String) second);
                } else if (second instanceof Boolean) {
                    out.writeByte(3);
                    out.writeBoolean((Boolean) second);
                }
            }
        }

        public void readData(DataInput in) throws IOException {
            try {
                first = (Expression) readDataSerializable(in);
                secondIsExpression = in.readBoolean();
                if (secondIsExpression) {
                    second = readDataSerializable(in);
                } else {
                    byte type = in.readByte();
                    if (type == 1) {
                        second = in.readLong();
                    } else if (type == 2) {
                        second = in.readUTF();
                    } else if (type == 3) {
                        second = in.readBoolean();
                    }
                }
            } catch (Exception e) {
                throw new IOException(e.getMessage());
            }
        }

        public Expression getFirst() {
            return first;
        }

        public Object getSecond() {
            return second;
        }
    }

    public static abstract class AbstractPredicate implements Predicate, DataSerializable {
        protected void writeDataSerializable(DataOutput out, DataSerializable ds) throws IOException {
            out.writeUTF(ds.getClass().getName());
            ds.writeData(out);
        }

        protected DataSerializable readDataSerializable(DataInput in) throws IOException {
            try {
                DataSerializable ds = (DataSerializable) Class.forName(in.readUTF()).newInstance();
                ds.readData(in);
                return ds;
            } catch (Exception e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    public static class AndOrPredicate extends AbstractPredicate implements IndexAwarePredicate {
        Predicate[] predicates;
        boolean and = false;

        public AndOrPredicate() {
        }

        public AndOrPredicate(boolean and, Predicate first, Predicate second) {
            this.and = and;
            predicates = new Predicate[]{first, second};
        }


        public AndOrPredicate(boolean and, Predicate... predicates) {
            this.and = and;
            this.predicates = predicates;
        }

        public boolean apply(MapEntry mapEntry) {
            for (Predicate predicate : predicates) {
                boolean result = predicate.apply(mapEntry);
                if (and && !result) return false;
                else if (!and && result) return true;
            }
            return and;
        }

        public void collectIndexedPredicates(List<IndexedPredicate> lsIndexPredicates) {
            if (and) {
                for (Predicate predicate : predicates) {
                    if (predicate instanceof IndexAwarePredicate) {
                        IndexAwarePredicate p = (IndexAwarePredicate) predicate;
                        p.collectIndexedPredicates(lsIndexPredicates);
                    }
                }
            }
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeBoolean(and);
            out.writeInt(predicates.length);
            for (Predicate predicate : predicates) {
                writeDataSerializable(out, (DataSerializable) predicate);
            }
        }

        public void readData(DataInput in) throws IOException {
            and = in.readBoolean();
            int len = in.readInt();
            predicates = new Predicate[len];
            for (int i = 0; i < len; i++) {
                predicates[i] = (Predicate) readDataSerializable(in);
            }
        }
    }

    public static Predicate instanceOf(final Class klass) {
        return new Predicate() {
            public boolean apply(MapEntry mapEntry) {
                Object value = mapEntry.getValue();
                if (value == null) return false;
                return klass.isAssignableFrom(value.getClass());
            }
        };
    }

    public static Predicate and(Predicate x, Predicate y) {
        return new AndOrPredicate(true, x, y);
    }


    public static Predicate or(Predicate x, Predicate y) {
        return new AndOrPredicate(false, x, y);
    }

    public static Predicate equal(final Expression x, final Object y) {
        return new EqualPredicate(x, y);
    }

    public static <T extends Comparable<T>> Predicate gt(Expression<? extends T> x, T y) {
        return new  GreaterLessPredicate(x, y, false, false);
    }

    public static <T extends Comparable<T>> Predicate lt(Expression<? extends T> x, T y) {
        return new  GreaterLessPredicate(x, y, false, true);        
    }

    public static <T extends Comparable<T>> Predicate between(Expression<? extends T> expression, T from, T to) {
        return new  BetweenPredicate(expression, from, to);
    }

    public static Predicate not(final Expression<Boolean> x) {
        return new Predicate() {
            public boolean apply(MapEntry entry) {
                Boolean value = x.getValue(entry);
                return Boolean.FALSE.equals(value);
            }
        };
    }

    public static Predicate not(final boolean value) {
        return new Predicate() {
            public boolean apply(MapEntry entry) {
                return Boolean.FALSE.equals(value);
            }
        };
    }

    public static GetExpression get(final Method method) {
        return new GetExpressionImpl(method);
    }

    public static GetExpression get(final String methodName) {
        return new GetExpressionImpl(methodName);
    }


    public static class GetExpressionImpl<T> implements GetExpression, DataSerializable {

        Object input;
        List<GetExpressionImpl<T>> ls = null;

        public GetExpressionImpl() {
        }

        public GetExpressionImpl(Object input) {
            this.input = input;
        }

        public GetExpression get(String methodName) {
            if (ls == null) {
                ls = new ArrayList();
            }
            ls.add(new GetExpressionImpl(methodName));
            return this;
        }

        public GetExpression get(Method method) {
            if (ls == null) {
                ls = new ArrayList();
            }
            ls.add(new GetExpressionImpl(method));
            return this;
        }

        public String getMethodName() {
            if (input instanceof Method) {
                return ((Method) input).getName();
            } else {
                return (String) input;
            }
        }

        public Object getValue(Object obj) {
            if (ls != null) {
                Object result = doGetValue(input, obj);
                for (GetExpressionImpl<T> e : ls) {
                    result = e.doGetValue(e.input, result);
                }
                return result;
            } else {
                return doGetValue(input, obj);
            }
        }

        private static Object doGetValue(Object input, Object obj) {
            if (obj instanceof MapEntry) {
                obj = ((MapEntry) obj).getValue();
            }
            try {
                if (input instanceof Method) {
                    return ((Method) input).invoke(obj);
                } else {
                    Method m = obj.getClass().getMethod((String) input, null);
                    return m.invoke(obj);
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        public void writeData(DataOutput out) throws IOException {
            boolean inputString = (input instanceof String);
            out.writeBoolean(inputString);
            if (inputString) {
               out.writeUTF((String) input);
            } else {
                DataSerializable ds = (DataSerializable) input;
                out.writeUTF(ds.getClass().getName());
                ds.writeData(out);
            }
        }

        public void readData(DataInput in) throws IOException {
            boolean inputString = in.readBoolean();
            if (inputString) {
                input = in.readUTF();
            } else {
                DataSerializable ds = null;
                try {
                    ds = (DataSerializable) Class.forName(in.readUTF()).newInstance();
                    ds.readData(in);
                } catch (Exception e) {
                    e.printStackTrace();
                } 
                input = ds;
            }
        }
    }

}