package com.sun.enterprise.module.bootstrap;

import javax.inject.Singleton;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.ErrorInformation;
import org.glassfish.hk2.api.ErrorService;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.MultiException;

@Singleton
public class DefaultErrorService implements ErrorService {
	@Override
	public void onFailure(ErrorInformation ei) throws MultiException {
		System.err.println(ei.getDescriptor());
		System.err.println(ei.getInjectee());
		ei.getAssociatedException().printStackTrace();
    }
	
}
