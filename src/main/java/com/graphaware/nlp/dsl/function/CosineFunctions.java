/*
 * Copyright (c) 2013-2018 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.nlp.dsl.function;

import com.graphaware.nlp.ml.similarity.CosineSimilarity;
import com.graphaware.nlp.ml.similarity.VectorProcessLogic;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CosineFunctions {

    @UserFunction(name = "ga.nlp.ml.similarity.cosine")
    @Description("ga.nlp.ml.similarity.cosine([1.2, 2.3, 3.1], [1.3,0,2.4], False) - compute cosine similarity between them; set the 3rd parameter to True if they are sparse vectors")
    public double cosine(
            @Name("vector1") List<Double> vector1,
            @Name("vector2") List<Double> vector2,
            @Name("is_sparse") Boolean is_sparse) {
        if (vector1 != null && vector2 != null) {
            if (!is_sparse) {
                double similarity = new CosineSimilarity().getSimilarity(vector1, vector2);
                return similarity;
            } else {
                double similarity = new VectorProcessLogic(null).getSimilarity(
                        vector1.stream().map(Double::floatValue).collect(Collectors.toList()),
                        vector2.stream().map(Double::floatValue).collect(Collectors.toList()),
                        is_sparse);
                return similarity;
            }
        } else {
            return 0.0d;
        }
    }

    protected Map<Long, Float> getVectorMap(List<Double> vector1) {
        Map<Long, Float> map
                = IntStream.range(0, vector1.size())
                .boxed()
                .collect(Collectors.toMap(i -> i.longValue(), i -> vector1.get(i).floatValue()));
        return map;
    }
}
