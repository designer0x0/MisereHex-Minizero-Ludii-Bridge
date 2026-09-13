package bridge;

import other.AI;
import utils.AIFactory;

/** One-off check: does AIFactory.createAI("Ludii AI") actually work? */
public class InspectLudiiAI {
    public static void main(String[] args) {
        AI ai = AIFactory.createAI("Ludii AI");
        System.out.println("Created: " + ai.getClass().getName() + " friendlyName=" + ai.name());
    }
}
