import { AccountActionType, type AccountCommand } from '@/service/accountAdmin';

type AccountSecurityAccount = Pick<
  AccountCommand,
  'user' | 'host' | 'authPlugin' | 'tlsRequirement' | 'tlsCipher' | 'tlsIssuer' | 'tlsSubject'
> & {
  authenticationPlugin?: string;
};

interface BuildAccountSecurityCommandParams {
  dataSourceId: number;
  actionType: AccountActionType;
  values: AccountCommand;
}

export function createAccountSecurityInitialValues(account: AccountSecurityAccount | null | undefined) {
  return {
    user: account?.user,
    host: account?.host,
    password: '',
    authPlugin: account?.authPlugin || account?.authenticationPlugin,
    tlsRequirement: account?.tlsRequirement,
    tlsCipher: account?.tlsCipher,
    tlsIssuer: account?.tlsIssuer,
    tlsSubject: account?.tlsSubject,
  };
}

export function buildAccountSecurityCommand(params: BuildAccountSecurityCommandParams): AccountCommand {
  const { dataSourceId, actionType, values } = params;
  const command: AccountCommand = {
    dataSourceId,
    user: values.user,
    host: values.host,
    password: values.password,
    authPlugin: values.authPlugin,
    tlsRequirement: values.tlsRequirement,
    actionType,
  };
  if (values.tlsRequirement === 'SPECIFIED') {
    command.tlsCipher = values.tlsCipher;
    command.tlsIssuer = values.tlsIssuer;
    command.tlsSubject = values.tlsSubject;
  }
  return removeEmptyFields(command);
}

function removeEmptyFields(command: AccountCommand): AccountCommand {
  return Object.fromEntries(
    Object.entries(command).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  ) as AccountCommand;
}
