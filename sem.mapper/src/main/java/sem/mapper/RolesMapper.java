package sem.mapper;

import java.awt.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.jgrapht.graph.DirectedSubgraph;
import org.jgrapht.graph.Subgraph;

import semantic.graph.SemGraph;
import semantic.graph.SemJGraphT;
import semantic.graph.SemanticEdge;
import semantic.graph.SemanticGraph;
import semantic.graph.SemanticNode;
import semantic.graph.vetypes.ContextNode;
import semantic.graph.vetypes.ContextNodeContent;
import semantic.graph.vetypes.GraphLabels;
import semantic.graph.vetypes.RoleEdge;
import semantic.graph.vetypes.RoleEdgeContent;
import semantic.graph.vetypes.SkolemNode;
import semantic.graph.vetypes.SkolemNodeContent;
import semantic.graph.vetypes.TermNode;
import semantic.graph.vetypes.TermNodeContent;


/**
 * This class maps the roles of the graph.
 * @author kkalouli
 *
 */
public class RolesMapper {
	private semantic.graph.SemanticGraph graph;
	private SemGraph depGraph;
	// will need passive for the passive graphs
	private boolean passive;
	// need it for verb coordination
	private boolean verbCoord;
	// need it for clausal coordination
	private boolean clausalCoord;
	// need it for non-verbal coordination
	boolean coord;
	// the verbal and noun forms of the main class "DepGraphToSemanticGraph"
	private ArrayList<String> verbalForms;
	private ArrayList<String> nounForms;
	// need to distinguish the subjs of the first and second verb in case of coordination
	private ArrayList<SemanticNode<?>> subjsOfFirstPred;
	private ArrayList<SemanticNode<?>> subjsOfSecondPred;
	// the edges that have been traversed when there is sentential coordination and the subgraphs of the separate sentences have to be build 
	// avoid infinite loop if original graph contains a cycle
	private ArrayList<SemanticEdge> traversedEdges;
	// graphs to be processed (in case there are more because of coordination)
	private ArrayList<SemGraph> graphsToProcess;
	private ArrayList<SemGraph> graphsToAdd;
	// coordinated edges to be added to the role graph
	private ArrayList<SemanticEdge> combEdges;
	// plain edges to be added to the role graph
	private HashMap<String,SemanticEdge> edgesToAdd;
	


	public RolesMapper(semantic.graph.SemanticGraph graph,ArrayList<String> verbalForms,  ArrayList<String> nounForms ){
		this.graph = graph;
		this.depGraph = graph.getDependencyGraph();
		passive = false;
		verbCoord = false;
		clausalCoord = false;
		coord = false;
		this.verbalForms = verbalForms;
		this.nounForms = nounForms;
		this.subjsOfFirstPred = new ArrayList<SemanticNode<?>>();
		this.subjsOfSecondPred = new ArrayList<SemanticNode<?>>();
		this.graphsToProcess = new ArrayList<SemGraph>();
		this.graphsToAdd = new ArrayList<SemGraph>();
		this.combEdges = new ArrayList<SemanticEdge>();
		this.traversedEdges = new ArrayList<SemanticEdge>();
		this.edgesToAdd = new HashMap<String, SemanticEdge>();
		this.traversedEdges = new ArrayList<SemanticEdge>();
		
	}


	/**
	 * Integrate all the roles to one graph. Go through the graphsToProcess and 
	 * find out if there is clausal coordination or not. If no, then proceed. If there is,
	 * then split sentences to separate graphs. 
	 */
	public void integrateAllRoles(){
		SemGraph depGraph = graph.getDependencyGraph();
		// at the first run, add the current graph to the graphsToProcess
		if (graphsToProcess.isEmpty())
			graphsToProcess.add((SemGraph) graph.getDependencyGraph());

		// go through the graphs to process and check their coordination and add the corresponding roles
		for(Iterator<SemGraph> iter = graphsToProcess.iterator(); iter.hasNext();){ 
			SemGraph current = iter.next();
			Set<SemanticEdge> innerEdges = current.getEdges();
			for (SemanticEdge edge : innerEdges){
				// check if there is coordination
				checkCoordination(edge);
				// if there is clausal coord, remove this graph from the graphs and break the loop
				if (clausalCoord == true) {
					iter.remove();
					edgesToAdd.clear();
					break;
				}
				String edgeLabel = edge.getLabel();
				// get start and finish node of the current edge
				SemanticNode<?> start = depGraph.getStartNode(edge);
				SemanticNode<?> finish = depGraph.getEndNode(edge);
				// integrate the coordinating roles
				integrateCoordinatingRoles(start, finish, edge, edgeLabel);
				verbCoord = false;
				coord = false;
			}
			
			// after dealing with coordination, add all the roles of the plain edges to the role graph		
			integratePlainEdges();
			edgesToAdd.clear();
			combEdges.clear();
			//}
			// if there is clausal coord, add the seperate sentences to the graphs and set all
			// other variables to false. Then rerun this method for these new graphs. 
			if (clausalCoord == true) {
				graphsToProcess.addAll(graphsToAdd);
				passive = false;
				clausalCoord = false;
				verbCoord = false;
				coord = false;
				integrateAllRoles();
				break;
			}
			passive = false;
			clausalCoord = false;		
		}
		// make sure that the role graph is non-empty in the case of an imperative with you. If empty, fill it in.
		if (graph.getRoleGraph().getNodes().isEmpty()){
			RoleEdge roleEdge = new RoleEdge(GraphLabels.SUBJ, new RoleEdgeContent());
			SkolemNodeContent subjNodeContent = new SkolemNodeContent();
			subjNodeContent.setSurface("you");
			subjNodeContent.setStem("you");
			subjNodeContent.setPosTag("PRP");
			subjNodeContent.setPartOfSpeech("PRP");
			subjNodeContent.setPosition(0);
			subjNodeContent.setDerived(false);
			subjNodeContent.setSkolem(subjNodeContent.getStem()+"_0");
			SkolemNode subjNode = new SkolemNode(subjNodeContent.getSkolem(), subjNodeContent);
			
			graph.addRoleEdge(roleEdge, graph.getRootNode(), subjNode);
			if (graph.getRootNode() instanceof SkolemNode && subjNode instanceof SkolemNode){
				// at this point set the contexts of all words that get involved to the role graph. the context is top at the moment 
				((SkolemNodeContent) graph.getRootNode().getContent()).setContext("top");
				((SkolemNodeContent) subjNode.getContent()).setContext("top");
			}
		}
		
	}

	/**
	 * Integrate all edges of the role graph apart from the combined node which is handled in the integrateCoordinatingRoles() method
	 */
	private void integratePlainEdges(){
		// list that holds all edges that have been added already
		ArrayList<SemanticEdge> traversed = new ArrayList<SemanticEdge>();
		// go through the edges of egdesToAdd and only add those that are not contained in the combEdges and that are not already traversed
		for (SemanticEdge edge: edgesToAdd.values()){
			if (combEdges.contains(edge))
				continue;
			else if (traversed.contains(edge))
				continue;
			SemanticNode<?> start = depGraph.getStartNode(edge);
			SemanticNode<?> finish = depGraph.getEndNode(edge);
			// integrate the basic roles for these edges
			integrateBasicRoles(start,finish,edge.getLabel());
			traversed.add(edge);
		}
		// list that holds all lists of begin-end-node-pairs that have alread been added
		ArrayList<ArrayList<SemanticNode<?>>> addedComb = new ArrayList<ArrayList<SemanticNode<?>>>();
		// go through the combEdges (= edges that involve coordinated node)
		for (SemanticEdge edge : combEdges){
			// get start and finish node of the current edge
			SemanticNode<?> start = depGraph.getStartNode(edge);
			SemanticNode<?> finish = depGraph.getEndNode(edge);
			SemanticNode<?> begin = start;
			SemanticNode<?> end = finish;	
			/* if one of the nodes of the role graph is the current start or finish node,
			then get the parent of that node from within the role graph (which will be a 
			coordinated node) because this will be the new parent/child of the edge
			*/
			for (SemanticNode<?> rNode : graph.getRoleGraph().getNodes()){
				if (rNode.equals(start)){
					// if this edge is an edge of a combined node, then the combined node gets to be the parent
					if (!graph.getRoleGraph().getInNeighbors(rNode).isEmpty()){
						SemanticNode<?> combNode = graph.getRoleGraph().getInNeighbors(rNode).iterator().next();
						begin = combNode;
					}
				} else if (rNode.equals(finish) ){
					// if this edge is an edge of a combined node, then the combined node gets to be the parent
					if (!graph.getRoleGraph().getInNeighbors(rNode).isEmpty()){
						SemanticNode<?> combNode = graph.getRoleGraph().getInNeighbors(rNode).iterator().next();
						end = combNode;
					}
				}
			}
			// create the list of begin-end-node-pair
			ArrayList<SemanticNode<?>> combNode = new ArrayList<SemanticNode<?>>();
			combNode.add(begin); 
			combNode.add(end);
			// only integrate the basic roles if the list does not contain the current list and the begin node is not the same as the end node
			if (!begin.equals(end) && !addedComb.contains(combNode)){
				integrateBasicRoles(begin,end,edge.getLabel());
				addedComb.add(combNode);
			}
		}
	}

	/**
	 * This method creates a subgraph having the given node as its root and the specified subedges and subnodes as its members. The nodeToExclude
	 * is the node-child of the root node that shouldnt be included in the subgraph.  
	 * This method is called when a sentence contains clausal coordination so that it is separated into subgraphs, each subgraph with one predicate.
	 * @param node
	 * @param nodeToExclude
	 * @param listOfSubEdges
	 * @param listOfSubNodes
	 * @return
	 */
	private SemGraph createSubgraph(SemanticNode<?> node, SemanticNode<?> nodeToExclude, Set<SemanticEdge> listOfSubEdges, Set<SemanticNode<?>> listOfSubNodes){
		//graph.displayDependencies();
		// got through all children edges of the specified node
		for (SemanticEdge subEdge : graph.getOutEdges(node)){
			traversedEdges.add(subEdge);
			// if the edge finishes with the nodeToEclude, move on
			if (graph.getFinishNode(subEdge).equals(nodeToExclude))
				continue;
			// otherwise, put into the list
			listOfSubEdges.add(subEdge);
			// put also the start anf finish node of that edge into the lists
			if (!listOfSubNodes.contains(graph.getStartNode(subEdge)))
				listOfSubNodes.add(graph.getStartNode(subEdge));
			if (!listOfSubNodes.contains(graph.getFinishNode(subEdge)))
				listOfSubNodes.add(graph.getFinishNode(subEdge));
			if (!traversedEdges.contains(subEdge)){
				// do the same for the finish node of the current edge (recursively so that children of children are also added)
				createSubgraph(graph.getFinishNode(subEdge), nodeToExclude, listOfSubEdges, listOfSubNodes);
			}
		}
		// create the subgraph
		return graph.getSubGraph(listOfSubNodes, listOfSubEdges);
	}
	
	/**
	 * Checks if the specified edge contains coordination and adds the nodes of the edge to the corresponding list.
	 * Sets the variables coord, verbCoord and clausalCoord. 
	 * @param edge
	 */
	private void checkCoordination(SemanticEdge edge){
		// get the start and finish node of the edge
		SemanticNode<?> start = depGraph.getStartNode(edge);
		SemanticNode<?> finish = depGraph.getEndNode(edge);
		// set the coordination  
		if (edge.getLabel().contains("conj") && !verbalForms.contains(((SkolemNodeContent) start.getContent()).getPosTag()) && !depGraph.getInEdges(start).isEmpty() ){			
				coord = true;
		}
		// set the verb coordination and get the subjs of the coordinated verbs
		else if (edge.getLabel().contains("conj")  && (verbalForms.contains(((SkolemNodeContent) start.getContent()).getPosTag()) || depGraph.getInEdges(start).isEmpty() ) ){
				verbCoord = true;
				for (SemanticEdge neighborEdge :graph.getOutEdges(graph.getFinishNode(edge))){ 
					if (neighborEdge.getLabel().contains("subj")){	
						if (!subjsOfSecondPred.contains(graph.getFinishNode(neighborEdge)))
							subjsOfSecondPred.add(graph.getFinishNode(neighborEdge));	
					}
				}
				for (SemanticEdge neighborEdge :graph.getOutEdges(graph.getStartNode(edge))){ 
					if (neighborEdge.getLabel().contains("subj")){	
						if (!subjsOfFirstPred.contains(graph.getFinishNode(neighborEdge)))
							subjsOfFirstPred.add(graph.getFinishNode(neighborEdge));	
					}
				}
		}
		// create some keys containing the label of the edge and the destination/source node, respectively
		// we need these to make sure that nodes involved in coordinated nodes are added as a whole to the coordinated node
		String keyDest = edge.getLabel()+"_"+edge.getDestVertexId();
		String keySource = edge.getLabel()+"_"+edge.getSourceVertexId();
		//add the key and the current edge if it doesnt exist
		if (edgesToAdd.get(keyDest) == null){
			edgesToAdd.put(keyDest, edge);
		}
		// if it already exists, then it means that this destination node is involved in a coordinated node and has to move to the combEdges
		else if (edgesToAdd.get(keyDest) != null){
			//add the previous same edge to combEdges
			combEdges.add(edgesToAdd.get(keyDest));
			// remove the previous same key from the hashmap
			edgesToAdd.remove(keyDest);
			// add the current edge to combEdges
			combEdges.add(edge);
		}
		//add the key and the current edge if it doesnt exist
		if (edgesToAdd.get(keySource) == null){
			edgesToAdd.put(keySource, edge);
		}
		// if it already exists, then it means that this source node is involved in a coordinated node and has to move to the combEdges
		else if (edgesToAdd.get(keySource) != null){
			//add the previous same edge to combEdges
			combEdges.add(edgesToAdd.get(keySource));
			// remove the previous same key from the hashmap
			edgesToAdd.remove(keySource);
			// add the current edge to combEdges
			combEdges.add(edge);
		}	
		
		// if there is verb coordination but the subjects of the two corodinated verbs are different, then there is clause coordination
		if (verbCoord == true && !subjsOfFirstPred.containsAll(subjsOfSecondPred)){
			coord = false;
			verbCoord = false;
			// create the subgraph with the start node of this edge as the root node
			Set<SemanticEdge> listOfSubEdges = new HashSet<SemanticEdge>();
			Set<SemanticNode<?>> listOfSubNodes = new HashSet<SemanticNode<?>>();
			SemGraph mainSubgraph = createSubgraph(start, finish, listOfSubEdges,listOfSubNodes);
			listOfSubEdges.clear();
			listOfSubNodes.clear();
			subjsOfFirstPred.clear();
			subjsOfSecondPred.clear();
			// create the subgraph with the finish node of this edge as the root node
			SemGraph subgraph = createSubgraph(finish, start, listOfSubEdges,listOfSubNodes);
			graphsToAdd.add(mainSubgraph);
			graphsToAdd.add(subgraph); 
			clausalCoord = true;		
		}
	}
	

	/**
	 * This method integrates the coordinated nodes: the two coordinated terms create a new term node which contains both of them. 
	 * @param start
	 * @param finish
	 * @param edge
	 * @param edgeLabel
	 */
	private void integrateCoordinatingRoles(SemanticNode<?> start, SemanticNode<?> finish, SemanticEdge edge, String edgeLabel){
		// the role that will have to be added at the end to the role graph
		String role = "";
	
		// only go here if there is coordination in the current edge 
		if (edgeLabel.contains("conj") ){
			// create the combined node of the coordinated terms and the edge of one of its children
			if (!graph.getRoleGraph().containsNode(start) && !graph.getRoleGraph().containsNode(finish)){
				TermNode combNode = new TermNode(start+"_"+edgeLabel.substring(5)+"_"+finish, new TermNodeContent());
				role = GraphLabels.IS_ELEMENT;
				RoleEdge combEdge1 = new RoleEdge(role, new RoleEdgeContent());
				graph.addRoleEdge(combEdge1, combNode, start);
				// create also the other edge of the other child
				RoleEdge combEdge2 = new RoleEdge(role, new RoleEdgeContent());
				graph.addRoleEdge(combEdge2, combNode, finish);	
			}
		} 
		// set the contexts of the start and finish nodes
		if (start instanceof SkolemNode && finish instanceof SkolemNode){
			// at this point set the contexts of all words that get involved to the role graph. the context is top at the moment 
			((SkolemNodeContent) start.getContent()).setContext("top");
			((SkolemNodeContent) finish.getContent()).setContext("top");
		}
	}

	/**
	 * Integrate the basic roles. These depend on the edges they are involved in. 
	 * @param start
	 * @param finish
	 * @param edgeLabel
	 * @param role
	 */
	private void integrateBasicRoles(SemanticNode<?> start, SemanticNode<?> finish, String edgeLabel){		
		String role = "";
		// switch of the different edge labels
		switch (edgeLabel){
		case "ccomp": role = GraphLabels.COMP;
		break;
		case "xcomp": role = GraphLabels.XCOMP;
		break;
		case "dobj" : role = GraphLabels.OBJ;
		break;
		case "iobj" : role = GraphLabels.XCOMP;
		break;
		// only add it when the verb coord is false; when true, it will be added later
		case "nsubj" : role = GraphLabels.SUBJ;
		break;
		// only add it when the verb coord is false; when true, it will be added later
		case "nsubjpass" : role = GraphLabels.OBJ;
		passive = true;
		break;
		case "nsubjpass:xsubj" : role = GraphLabels.OBJ;
		passive = true;
		break;
		// only add it when the verb coord is false; when true, it will be added later
		case "csubj" : role = GraphLabels.SUBJ;
		break;
		// only add it when the verb coord is false; when true, it will be added later
		case "csubjpass" : role = GraphLabels.OBJ;	
		passive = true;
		break;
		case "nmod:agent" : if (passive == true){ 
			role = GraphLabels.SUBJ;
		}	
		break;
		case "nmod:by" : if (passive == true){ 
			role = GraphLabels.SUBJ;
		}	
		break;
		// only add it when the verb coord is false; when true, it will be added later
		case "nsubj:xsubj": role = GraphLabels.SUBJ;
		break;
		case "amod": role = GraphLabels.AMOD;
		break;
		case "advmod": role = GraphLabels.AMOD;
		break;
		case "acl:relcl": role = GraphLabels.RESTRICTION;
		break;
		case "compound":role = GraphLabels.RESTRICTION;
		break;
		case "appos":role = GraphLabels.RESTRICTION;
		break;
		case "nummod": role = GraphLabels.RESTRICTION;
		}
		// nmod can have difefrent subtypes, therefore it is put here and not within the switch 
		if (role.equals("") && edgeLabel.contains("nmod")){
			role = GraphLabels.NMOD;
		} else if (edgeLabel.contains("acl")){
			role = GraphLabels.NMOD;	
		}  else if (edgeLabel.contains("advcl")){
			role = GraphLabels.AMOD;	
		}
		
		// if the role is not empty, create the edge for this role and set the contexts
		if (!role.equals("")){
			RoleEdge roleEdge = new RoleEdge(role, new RoleEdgeContent());
			graph.addRoleEdge(roleEdge, start, finish);
			if (start instanceof SkolemNode && finish instanceof SkolemNode){
				// at this point set the contexts of all words that get involved to the role graph. the context is top at the moment 
				((SkolemNodeContent) start.getContent()).setContext("top");
				((SkolemNodeContent) finish.getContent()).setContext("top");
			}
		}
	}


}
