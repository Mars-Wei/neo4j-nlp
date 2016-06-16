/*
 * Copyright (c) 2013-2016 GraphAware
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
package com.graphaware.nlp.proc;

import com.graphaware.nlp.conceptnet5.ConceptNet5Importer;
import com.graphaware.nlp.domain.AnnotatedText;
import com.graphaware.nlp.domain.Labels;
import com.graphaware.nlp.domain.Properties;
import com.graphaware.nlp.domain.Tag;
import com.graphaware.nlp.logic.FeatureBasedProcessLogic;
import com.graphaware.nlp.processor.TextProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.collection.RawIterator;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.kernel.api.proc.Neo4jTypes;
import org.neo4j.kernel.api.proc.ProcedureSignature;
import static org.neo4j.kernel.api.proc.ProcedureSignature.procedureName;
import static org.neo4j.kernel.api.proc.ProcedureSignature.procedureSignature;

public class NLPProcedure {

    private final TextProcessor textProcessor;
    private final ConceptNet5Importer conceptnet5Importer;
    private final GraphDatabaseService database;

    private static final String PARAMETER_NAME_INPUT = "input";
    private static final String PARAMETER_NAME_TEXT = "text";
    private static final String PARAMETER_NAME_ANNOTATED_TEXT = "node";
    private static final String PARAMETER_NAME_DEPTH = "depth";
    private static final String PARAMETER_NAME_ID = "id";
    private static final String PARAMETER_NAME_INPUT_OUTPUT = "result";

    private final FeatureBasedProcessLogic featureBusinessLogic;

    public NLPProcedure(GraphDatabaseService database, FeatureBasedProcessLogic featureBusinessLogic) {
        this.database = database;
        this.textProcessor = new TextProcessor();
        this.conceptnet5Importer = new ConceptNet5Importer.Builder("http://conceptnet5.media.mit.edu/data/5.4", textProcessor)
                .build();
        this.featureBusinessLogic = featureBusinessLogic;
    }

    public CallableProcedure.BasicProcedure annotate() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("annotate"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTNode).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                Object id = inputParams.get(PARAMETER_NAME_ID);
                Node annotatedText = checkIfExist(id);
                if (annotatedText == null) {
                    AnnotatedText annotateText = textProcessor.annotateText(text, id);
                    annotatedText = annotateText.storeOnGraph(database);
                }
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedText}).iterator());
            }

            private Node checkIfExist(Object id) {
                if (id != null) {
                    ResourceIterator<Node> findNodes = database.findNodes(Labels.AnnotatedText, Properties.PROPERTY_ID, id);
                    if (findNodes.hasNext()) {
                        return findNodes.next();
                    }
                }
                return null;
            }
        };
    }

    public CallableProcedure.BasicProcedure concept() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("concept"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTNode).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                Node annotatedNode = (Node) inputParams.get(PARAMETER_NAME_ANNOTATED_TEXT);
                int depth = ((Long)inputParams.getOrDefault(PARAMETER_NAME_DEPTH, 2)).intValue();
                try (Transaction beginTx = database.beginTx()) {
                    ResourceIterator<Node> tags = getAnnotatedTextTags(annotatedNode);
                    while (tags.hasNext()) {
                        final Tag tag = Tag.createTag(tags.next());
                        List<Tag> conceptTags = conceptnet5Importer.importHierarchy(tag, "en", depth);
                        conceptTags.stream().forEach((newTag) -> {
                            newTag.storeOnGraph(database);
                        });
                        tag.storeOnGraph(database);
                    }
                    beginTx.success();
                }
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{annotatedNode}).iterator());
            }

            private ResourceIterator<Node> getAnnotatedTextTags(Node annotatedNode) throws QueryExecutionException {
              Map<String, Object> params = new HashMap<>();
              params.put("id", annotatedNode.getId());
              Result queryRes = database.execute("MATCH (n:AnnotatedText)-[*..2]->(t:Tag) where id(n) = {id} return t", params);
              ResourceIterator<Node> tags = queryRes.columnAs("t");
                return tags;
            }
        };
    }

    public CallableProcedure.BasicProcedure computeAll() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("cosine", "compute"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTAny)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTInteger).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(CallableProcedure.Context ctx, Object[] input) throws ProcedureException {
                int processed = 0;
                List<Long> firstNodeIds = getNodesFromInput(input);
                processed = featureBusinessLogic.computeFeatureSimilarityForNodes(firstNodeIds);
                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Integer[]{processed}).iterator());
            }
        };
    }

    protected List<Long> getNodesFromInput(Object[] input) {
        List<Long> firstNodeIds = new ArrayList<>();
        if (input[0] == null) {
            return null;
        } else if (input[0] instanceof Node) {
            firstNodeIds.add(((Node) input[0]).getId());
            return firstNodeIds;
        } else if (input[0] instanceof Map) {
            Map<String, Object> nodesMap = (Map) input[0];
            nodesMap.values().stream().filter((element) -> (element instanceof Node)).forEach((element) -> {
                firstNodeIds.add(((Node) element).getId());
            });
            if (!firstNodeIds.isEmpty()) {
                return firstNodeIds;
            } else {
                return null;
            }
        } else {
            throw new RuntimeException("Invalid input parameters " + input[0]);
        }
    }

    protected static ProcedureSignature.ProcedureName getProcedureName(String... procedureName) {
        String namespace[] = new String[2 + procedureName.length];
        int i = 0;
        namespace[i++] = "ga";
        namespace[i++] = "nlp";

        for (String value : procedureName) {
            namespace[i++] = value;
        }
        return procedureName(namespace);
    }

    protected void checkIsMap(Object object) throws RuntimeException {
        if (!(object instanceof Map)) {
            throw new RuntimeException("Input parameter is not a map");
        }
    }

}