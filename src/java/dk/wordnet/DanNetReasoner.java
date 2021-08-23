package dk.wordnet;

import org.apache.jena.graph.Capabilities;
import org.apache.jena.graph.Graph;
import org.apache.jena.reasoner.*;
import org.apache.jena.reasoner.rulesys.*;

import java.util.List;

/**
 * Based on OWLMicroReasoner implementation, but with a reduced set of rules.
 *
 * The rules are bundled as a resource in "etc/dannet.rules". They are based on
 * the rules included with Jena for the OWLMicroReasoner, where unused rules
 * have been commented out using ### (i.e. triple line comment symbols).
 */
public class DanNetReasoner extends GenericRuleReasoner implements Reasoner {

    protected static List<Rule> microRuleSet;

    public static List<Rule> loadRules() {
        if (microRuleSet == null) microRuleSet = loadRules("etc/dannet.rules");
        return microRuleSet;
    }

    public DanNetReasoner(ReasonerFactory factory) {
        super(loadRules(), factory);
        setOWLTranslation(true);
        setMode(HYBRID);
        setTransitiveClosureCaching(true);
    }

    @Override
    public Capabilities getGraphCapabilities() {
        if (capabilities == null) {
            capabilities = new BaseInfGraph.InfFindSafeCapabilities();
        }
        return capabilities;
    }

    @Override
    public InfGraph bind(Graph data) throws ReasonerException {
        InfGraph graph = super.bind(data);
        ((FBRuleInfGraph)graph).setDatatypeRangeValidation(true);
        return graph;
    }
}
