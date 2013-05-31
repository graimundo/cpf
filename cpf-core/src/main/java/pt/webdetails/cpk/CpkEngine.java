/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
package pt.webdetails.cpk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import pt.webdetails.cpf.Util;
import pt.webdetails.cpf.repository.BaseRepositoryAccess;
import pt.webdetails.cpf.repository.IRepositoryFile;
import pt.webdetails.cpk.elements.IElement;
import pt.webdetails.cpk.elements.IElementType;
import pt.webdetails.cpk.security.IAccessControl;

/**
 *
 * @author Pedro Alves<pedro.alves@webdetails.pt>
 */
public class CpkEngine {

    private static CpkEngine instance;
    protected static Log logger = LogFactory.getLog(CpkEngine.class);
    private Document cpkDoc;
    private TreeMap<String, IElement> elementsMap;
    private HashMap<String, IElementType> elementTypesMap;
    private static List reserverdWords = Arrays.asList("refresh", "status", "reload");
    private String defaultElementName = null;
    private ICpkEnvironment cpkEnv;


    
    private CpkEngine() {
        // Starting elementEngine
        logger.debug("Starting ElementEngine");
        elementsMap = new TreeMap<String, IElement>();
        elementTypesMap = new HashMap<String, IElementType>();
    }
    
    

    private CpkEngine(ICpkEnvironment environment) {
        this();
        this.cpkEnv = environment;
    }


    public static CpkEngine getInstanceWithEnv(ICpkEnvironment environment) {
        if (instance == null) {
            instance = new CpkEngine(environment);
            try {
              instance.initialize();
            } catch (Exception ex) {
              logger.fatal("Error initializing CpkEngine: " + Util.getExceptionDescription(ex));
            }                                    
            
        }
        return instance;
    }

    public static CpkEngine getInstance() {

        if (instance == null) {

            instance = new CpkEngine();
            try {
              instance.initialize();
            } catch (Exception ex) {
              logger.fatal("Error initializing CpkEngine: " + Util.getExceptionDescription(ex));
            }                                    
        }

        return instance;

    }

    protected synchronized void initialize() throws DocumentException, IOException {
        logger.info("Initializing CPK Plugin " + cpkEnv.getPluginUtils().getPluginName().toUpperCase());
        reload();

    }

    
    
    public ICpkEnvironment getEnvironment() {
      return this.cpkEnv;
    }
    
    /**
     *
     * Reloads or initializes the ElementManager
     *
     */
    public void reload() throws DocumentException, IOException {

        // Clean the types
        elementsMap.clear();
        elementTypesMap.clear();
        cpkEnv.reload();
        SAXReader reader;
        Document cpkDoc;
        IRepositoryFile repFile = cpkEnv.getRepositoryAccess().getSettingsFile("cpk.xml", BaseRepositoryAccess.FileAccess.READ);
        ByteArrayInputStream bis = new ByteArrayInputStream(repFile.getData());
        
        try {
        reader = new SAXReader();
        cpkDoc = reader.read(bis);
        setCpkDoc(cpkDoc);
        } finally {
            bis.close();
        }
       
        List<Node> elementTypeNodes = cpkDoc.selectNodes("/cpk/elementTypes/elementType");
        defaultElementName = cpkDoc.selectSingleNode("/cpk/elementTypes").valueOf("@defaultElement").toLowerCase();

        for (Node node : elementTypeNodes) {

            // Loop and instantiate the element types
            String clazz = node.valueOf("./@class");
            logger.debug("Found elementType: " + clazz);

            IElementType elementType;
            try {
                Object o[] = new Object[1];
                o[0]=cpkEnv.getPluginUtils(); //Should get the environment
                elementType = (IElementType) Class.forName(clazz).getDeclaredConstructors()[0].newInstance(o);

                // Store it
                elementTypesMap.put(elementType.getType(), elementType);

            } catch (Exception ex) {
                logger.error("Error initializing element type " + clazz + ": " + Util.getExceptionDescription(ex));
                continue;
            }

            // Now that we have the class, scan the elements
            List<IElement> elements = elementType.scanElements(getCpkDoc().selectSingleNode("/cpk/elementTypes/elementType[@class='" + clazz + "']"));

            // Register them in the map. We don't support duplicates, and we don't allow some reserved names
            for (IElement element : elements) {

                String key = element.getId().toLowerCase();

                if (reserverdWords.contains(key)) {

                    logger.warn("Element with reserved work '" + key + "' can't be registred: " + element.toString());

                } else {
                    // All ok
                    if (!element.getName().startsWith("_")) {
                        elementsMap.put(element.getId().toLowerCase(), element);
                    }
                }

            }

            logger.debug("Initialization for " + elementType.getType() + " successfull. Registred " + elements.size() + " elements");

        }

    }

    public Document getCpkDoc() {
        return cpkDoc;
    }

    public void setCpkDoc(Document cpkDoc) {
        this.cpkDoc = cpkDoc;
    }

    public TreeMap<String, IElement> getElementsMap() {
        return elementsMap;
    }

    public IElementType getElementType(String type) {
        return elementTypesMap.get(type);
    }

    /**
     * Gets the element corresponding to the registred key
     *
     * @param key
     * @return
     */
    public IElement getElement(String key) {
        return this.elementsMap.get(key);
    }

    /**
     * Gets the element corresponding to the registred key
     *
     * @param key
     * @return
     */
    public IElement getDefaultElement() {
        IElement element = elementsMap.get(defaultElementName);
        if (element == null) {
            for (IElement e : this.elementsMap.values()) {
                if (CpkEngine.getInstance().getElementType(e.getElementType()).isShowInSitemap()) {
                    element = e;
                    break;
                }
            }
        }
        return element;
    }

    /**
     *
     * @return
     */
    public String getStatus() {

        IAccessControl accessControl = cpkEnv.getAccessControl();
        StringBuilder out = new StringBuilder();

        out.append("--------------------------------\n");
        out.append("   ").append(cpkEnv.getPluginName()).append(" Status\n");
        out.append("--------------------------------\n");
        out.append("\n");

        // Show the different entities

        out.append(elementTypesMap.size()).append(" registered entity types\nDefault element: [").append(defaultElementName).append("]\n");
        out.append("\n");
        out.append("End Points\n");

        for (String key : elementsMap.keySet()) {

            IElement myElement = elementsMap.get(key);
            if (accessControl.isAllowed(myElement)) {
                out.append("   [").append(key).append("]: \t").append(myElement.toString()).append("\n\n");
            }

        }

        return out.toString();

    }
   

    public Map<String, IElementType> getElementTypes() {
        return this.elementTypesMap;
    }

    public String getElementsJson() {
        ObjectMapper mapper = new ObjectMapper();
        String json = null;

        try {
            json = mapper.writeValueAsString(this.elementsMap.values());
        } catch (IOException ex) {
            logger.error("Error getting json elements", ex);
        }

        return json;
    }

    
    

}
